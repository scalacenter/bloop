package bloop.tracing

import brave.propagation.SamplingFlags
import brave.propagation.TraceContext
import brave.propagation.TraceContextOrSamplingFlags
import brave.Span
import brave.Tracer
import monix.eval.Task
import monix.execution.misc.NonFatal
import scala.util.Properties
import zipkin2.codec.SpanBytesEncoder.JSON_V1
import zipkin2.codec.SpanBytesEncoder.JSON_V2

final class BraveTracer private (
    tracer: Tracer,
    val currentSpan: Span,
    closeCurrentSpan: () => Unit,
    traceProperties: TraceProperties
) {
  def startNewChildTracer(name: String, tags: (String, String)*): BraveTracer = {
    val span = tags.foldLeft(tracer.newChild(currentSpan.context).name(name)) {
      case (span, (tagKey, tagValue)) => span.tag(tagKey, tagValue)
    }

    span.start()
    TraceProperties.TraceStartAnnotation.getOrGlobal(traceProperties).foreach(span.annotate)
    new BraveTracer(tracer, span, () => {
      TraceProperties.TraceEndAnnotation.getOrGlobal(traceProperties).foreach(span.annotate)
      span.finish()
    }, traceProperties)
  }

  def trace[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => T
  ): T = {
    traceInternal(name, verbose = false, tags: _*)(thunk)
  }

  def traceVerbose[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => T
  ): T = {
    traceInternal(name, verbose = true, tags: _*)(thunk)
  }

  def traceTask[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = {
    traceTaskInternal(name, verbose = false, tags: _*)(thunk)
  }

  def traceTaskVerbose[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = {
    traceTaskInternal(name, verbose = true, tags: _*)(thunk)
  }

  private def traceInternal[T](name: String, verbose: Boolean, tags: (String, String)*)(
      thunk: BraveTracer => T
  ): T = {
    if (!verbose || TraceProperties.Verbose.getOrGlobal(traceProperties)) {
      val newTracer = startNewChildTracer(name, tags: _*)
      try thunk(newTracer) // Don't catch and report errors in spans
      catch {
        case NonFatal(t) =>
          newTracer.currentSpan.error(t)
          throw t
      } finally {
        try newTracer.terminate()
        catch {
          case NonFatal(t) => ()
        }
      }
    } else {
      thunk(this)
    }
  }

  private def traceTaskInternal[T](name: String, verbose: Boolean, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = {
    if (!verbose || TraceProperties.Verbose.getOrGlobal(traceProperties)) {
      val newTracer = startNewChildTracer(name, tags: _*)
      thunk(newTracer)
        .doOnCancel(Task(newTracer.terminate()))
        .doOnFinish {
          case None => Task.eval(newTracer.terminate())
          case Some(value) =>
            Task.eval {
              newTracer.currentSpan.error(value)
              newTracer.terminate()
            }
        }
    } else {
      thunk(this)
    }
  }

  def terminate(): Unit = this.synchronized {
    // Guarantee we never throw, even though brave APIs should already
    try closeCurrentSpan()
    catch { case t: Throwable => () }
  }

  /** Create an independent tracer that propagates this current context
   * and that whose completion in zipkin will happen independently. This
   * is ideal for tracing background tasks that outlive their parent trace. */
  def toIndependentTracer(
      name: String,
      traceProperties: TraceProperties,
      tags: (String, String)*
  ): BraveTracer =
    BraveTracer(name, traceProperties, Some(currentSpan.context), tags: _*)
}

object BraveTracer {
  import brave._
  import zipkin2.reporter.AsyncReporter
  import zipkin2.reporter.urlconnection.URLConnectionSender

  def apply(name: String, tags: (String, String)*): BraveTracer = {
    BraveTracer(name, TraceProperties.Global.properties, None, tags: _*)
  }

  def apply(
      name: String,
      traceProperties: TraceProperties,
      tags: (String, String)*
  ): BraveTracer = {
    BraveTracer(name, traceProperties, None, tags: _*)
  }

  def apply(
      name: String,
      traceProperties: TraceProperties,
      ctx: Option[TraceContext],
      tags: (String, String)*
  ): BraveTracer = {

    val url = TraceProperties.ZipkinServerUrl.getOrGlobal(traceProperties)
    val sender = URLConnectionSender.create(url)
    val jsonVersion = if (url.contains("/api/v1")) {
      JSON_V1
    } else {
      JSON_V2
    }
    val spanReporter = AsyncReporter.builder(sender).build(jsonVersion)

    val tracing = Tracing
      .newBuilder()
      .localServiceName(TraceProperties.LocalServiceName.getOrGlobal(traceProperties))
      .spanReporter(spanReporter)
      .build()
    val tracer = tracing.tracer()
    val newParentTrace = ctx
      .map(c => tracer.newChild(c))
      .getOrElse(
        if (TraceProperties.Debug.getOrGlobal(traceProperties)) {
          tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.DEBUG))
        } else {
          tracer.newTrace()
        }
      )
    val rootSpan = tags.foldLeft(newParentTrace.name(name)) {
      case (span, (tagKey, tagValue)) => span.tag(tagKey, tagValue)
    }
    rootSpan.start()
    TraceProperties.TraceStartAnnotation.getOrGlobal(traceProperties).foreach(rootSpan.annotate)
    val closeEverything = () => {
      TraceProperties.TraceEndAnnotation.getOrGlobal(traceProperties).foreach(rootSpan.annotate)
      rootSpan.finish()
      tracing.close()
      spanReporter.flush()
    }
    new BraveTracer(tracer, rootSpan, closeEverything, traceProperties)
  }
}
