package bloop.tracing

import brave.{Span, Tracer}
import brave.propagation.TraceContext
import monix.eval.Task
import monix.execution.misc.NonFatal
import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import java.util.concurrent.TimeUnit
import zipkin2.codec.SpanBytesEncoder.{JSON_V1, JSON_V2}

final class BraveTracer private (
    tracer: Tracer,
    val currentSpan: Span,
    closeCurrentSpan: () => Unit
) {
  def startNewChildTracer(name: String, tags: (String, String)*): BraveTracer = {
    import brave.propagation.TraceContext
    val span = tags.foldLeft(tracer.newChild(currentSpan.context).name(name)) {
      case (span, (tagKey, tagValue)) => span.tag(tagKey, tagValue)
    }

    span.start()
    new BraveTracer(tracer, span, () => span.finish())
  }

  def trace[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => T
  ): T = {
    val newTracer = startNewChildTracer(name, tags: _*)
    try thunk(newTracer) // Don't catch and report errors in spans
    catch {
      case NonFatal(t) =>
        newTracer.currentSpan.error(t)
        throw t
    } finally {
      try newTracer.terminate()
      catch { case NonFatal(t) => () }
    }
  }

  def traceTask[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = {
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
  }

  def terminate(): Unit = this.synchronized {
    // Guarantee we never throw, even though brave APIs should already
    try closeCurrentSpan()
    catch { case t: Throwable => () }
  }

  /** Create an independent tracer that propagates this current context
   * and that whose completion in zipkin will happen independently. This
   * is ideal for tracing background tasks that outlive their parent trace. */
  def toIndependentTracer(name: String, tags: (String, String)*): BraveTracer =
    BraveTracer(name, Some(currentSpan.context), tags: _*)
}

object BraveTracer {
  import brave._
  import brave.sampler.Sampler
  import zipkin2.reporter.AsyncReporter
  import zipkin2.reporter.urlconnection.URLConnectionSender
  val zipkinServerUrl = Option(System.getProperty("zipkin.server.url")).getOrElse(
    "http://127.0.0.1:9411/api/v2/spans"
  )
  val debugTrace = Properties.propOrFalse("zipkin.trace.debug")

  val sender = URLConnectionSender.create(zipkinServerUrl)
  val jsonVersion = if (zipkinServerUrl.contains("/api/v1")) {
    JSON_V1
  } else {
    JSON_V2
  }
  val spanReporter = AsyncReporter.builder(sender).build(jsonVersion)
  def apply(name: String, tags: (String, String)*): BraveTracer = {
    BraveTracer(name, None, tags: _*)
  }

  def apply(name: String, ctx: Option[TraceContext], tags: (String, String)*): BraveTracer = {
    import java.util.concurrent.TimeUnit
    val tracing = Tracing
      .newBuilder()
      .localServiceName("bloop")
      .spanReporter(spanReporter)
      .build()
    val tracer = tracing.tracer()
    val newParentTrace = ctx
      .map(c => tracer.newChild(c))
      .getOrElse(
        if (debugTrace) {
          val c = TraceContext
            .newBuilder()
            .traceId(util.Random.nextLong())
            .spanId(util.Random.nextLong())
            .debug(true)
            .build()
          tracer.newChild(c)
        } else {
          tracer.newTrace()
        }
      )
    val rootSpan = tags.foldLeft(newParentTrace.name(name)) {
      case (span, (tagKey, tagValue)) => span.tag(tagKey, tagValue)
    }
    rootSpan.start()
    val closeEverything = () => {
      rootSpan.finish()
      tracing.close()
      spanReporter.flush()
    }
    new BraveTracer(tracer, rootSpan, closeEverything)
  }
}
