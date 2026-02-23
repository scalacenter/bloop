package bloop.tracing

import java.util.concurrent.ConcurrentHashMap
import java.nio.file.{Paths => NioPaths, Files}
import java.net.URI

import scala.util.control.NonFatal

import bloop.task.Task
import bloop.io.AbsolutePath

import brave.Span
import brave.Tracer
import brave.propagation.SamplingFlags
import brave.propagation.TraceContext
import brave.propagation.TraceContextOrSamplingFlags
import zipkin2.codec.SpanBytesEncoder.JSON_V1
import zipkin2.codec.SpanBytesEncoder.JSON_V2

sealed trait BraveTracer {
  def startNewChildTracer(name: String, tags: (String, String)*): BraveTracer

  def trace[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => T
  ): T

  def tag(key: String, value: String): Unit

  def traceVerbose[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => T
  ): T

  def traceTask[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T]

  def traceTaskVerbose[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T]

  def terminate(): Unit

  def currentSpan: Option[Span]

  def toIndependentTracer(
      name: String,
      traceProperties: TraceProperties,
      tags: (String, String)*
  ): BraveTracer

}

object NoopTracer extends BraveTracer {

  override def startNewChildTracer(name: String, tags: (String, String)*): BraveTracer = this

  override def tag(key: String, value: String): Unit = ()

  override def trace[T](name: String, tags: (String, String)*)(thunk: BraveTracer => T): T = thunk(
    this
  )

  override def traceVerbose[T](name: String, tags: (String, String)*)(thunk: BraveTracer => T): T =
    thunk(this)

  override def traceTask[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = thunk(this)

  override def traceTaskVerbose[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = thunk(this)

  override def terminate(): Unit = ()

  override def currentSpan: Option[Span] = None

  def toIndependentTracer(
      name: String,
      traceProperties: TraceProperties,
      tags: (String, String)*
  ): BraveTracer = this

}

object BraveTracer {

  def apply(name: String, properties: TraceProperties, tags: (String, String)*): BraveTracer = {
    BraveTracer(name, properties, None, tags: _*)
  }

  def apply(
      name: String,
      properties: TraceProperties,
      ctx: Option[TraceContext],
      tags: (String, String)*
  ): BraveTracer = {
    if (properties.enabled) {
      BraveTracerInternal(name, properties, ctx, tags: _*)
    } else {
      val buildUri = tags.collectFirst { case ("workspace.dir", value) => value }
      buildUri match {
        case Some(uri) =>
          val workspaceDir = AbsolutePath(NioPaths.get(uri))
          val traceFile = workspaceDir.resolve("compilation-trace.json")
          if (traceFile.exists) {
            val projectName = tags
              .collectFirst { case ("compile.target", value) => value }
              .getOrElse(name)
            new CompilationTraceTracer(projectName, traceFile, System.currentTimeMillis())
          } else {
            NoopTracer
          }
        case None => NoopTracer
      }
    }
  }
}

final class BraveTracerInternal private (
    tracer: Tracer,
    val _currentSpan: Span,
    closeCurrentSpan: () => Unit,
    properties: TraceProperties
) extends BraveTracer {

  override def tag(key: String, value: String): Unit = {
    _currentSpan.tag(key, value)
    ()
  }

  def currentSpan = Some(_currentSpan)

  def startNewChildTracer(name: String, tags: (String, String)*): BraveTracer = {
    val span = tags.foldLeft(tracer.newChild(_currentSpan.context).name(name)) {
      case (span, (tagKey, tagValue)) => span.tag(tagKey, tagValue)
    }

    span.start()
    properties.traceStartAnnotation.foreach(span.annotate)
    val closeHandler = () => {
      properties.traceEndAnnotation.foreach(span.annotate)
      span.finish()
    }

    new BraveTracerInternal(tracer, span, closeHandler, properties)
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
    if (!verbose || properties.verbose) {
      val newTracer = startNewChildTracer(name, tags: _*)
      try thunk(newTracer) // Don't catch and report errors in spans
      catch {
        case NonFatal(t) =>
          newTracer.currentSpan.foreach(_.error(t))
          throw t
      } finally {
        try newTracer.terminate()
        catch {
          case NonFatal(_) => ()
        }
      }
    } else {
      thunk(this)
    }
  }

  private def traceTaskInternal[T](name: String, verbose: Boolean, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = {
    if (!verbose || properties.verbose) {
      val newTracer = startNewChildTracer(name, tags: _*)
      thunk(newTracer)
        .doOnCancel(Task(newTracer.terminate()))
        .doOnFinish {
          case None => Task.eval(newTracer.terminate())
          case Some(value) =>
            Task.eval {
              newTracer.currentSpan.foreach(_.error(value))
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
    catch { case _: Throwable => () }
  }

  /**
   * Create an independent tracer that propagates this current context
   * and that whose completion in zipkin will happen independently. This
   * is ideal for tracing background tasks that outlive their parent trace.
   */
  def toIndependentTracer(
      name: String,
      traceProperties: TraceProperties,
      tags: (String, String)*
  ): BraveTracer =
    BraveTracer(name, traceProperties, Some(_currentSpan.context), tags: _*)
}

object BraveTracerInternal {
  import brave._
  import zipkin2.reporter.AsyncReporter
  import zipkin2.reporter.urlconnection.URLConnectionSender

  private val reporterCache = new ConcurrentHashMap[String, AsyncReporter[zipkin2.Span]]()
  private def reporterFor(url: String): AsyncReporter[zipkin2.Span] = {
    def newReporter(url: String): AsyncReporter[zipkin2.Span] = {
      val sender = URLConnectionSender.create(url)
      val jsonVersion = if (url.contains("/api/v1")) JSON_V1 else JSON_V2
      AsyncReporter.builder(sender).build(jsonVersion)
    }
    reporterCache.computeIfAbsent(url, newReporter)
  }

  def apply(
      name: String,
      properties: TraceProperties,
      ctx: Option[TraceContext],
      tags: (String, String)*
  ): BraveTracer = {

    val url = properties.serverUrl
    val spanReporter = reporterFor(url)

    val tracing = Tracing
      .newBuilder()
      .localServiceName(properties.localServiceName)
      .spanReporter(spanReporter)
      .build()

    val tracer = tracing.tracer()
    val newParentTrace = ctx
      .map(c => tracer.newChild(c))
      .getOrElse(
        if (properties.debugTracing) {
          tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.DEBUG))
        } else {
          tracer.newTrace()
        }
      )

    val rootSpan = tags.foldLeft(newParentTrace.name(name)) {
      case (span, (tagKey, tagValue)) => span.tag(tagKey, tagValue)
    }

    rootSpan.start()
    properties.traceStartAnnotation.foreach(rootSpan.annotate)
    val closeEverything = () => {
      properties.traceEndAnnotation.foreach(rootSpan.annotate)
      rootSpan.finish()
      tracing.close()
      spanReporter.flush()
    }

    new BraveTracerInternal(tracer, rootSpan, closeEverything, properties)
  }
}

final class CompilationTraceTracer(
    project: String,
    traceFile: bloop.io.AbsolutePath,
    startTime: Long
) extends BraveTracer {
  import bloop.tracing.CompilationTrace
  import bloop.tracing.TraceArtifacts
  import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
  import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig

  private val tags = new ConcurrentHashMap[String, String]()

  override def startNewChildTracer(name: String, tags: (String, String)*): BraveTracer = this
  override def tag(key: String, value: String): Unit = {
    this.tags.put(key, value)
    ()
  }

  override def trace[T](name: String, tags: (String, String)*)(thunk: BraveTracer => T): T =
    thunk(this)
  override def traceVerbose[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => T
  ): T = thunk(this)
  override def traceTask[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = thunk(this)
  override def traceTaskVerbose[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = thunk(this)

  override def terminate(): Unit = {
    val durationMs = System.currentTimeMillis() - startTime
    val isNoOp = tags.getOrDefault("isNoOp", "false").toBoolean
    val analysisOut = tags.getOrDefault("analysis", "")
    val classesDir = tags.getOrDefault("classesDir", "")
    val artifacts = TraceArtifacts(classesDir, analysisOut)
    val fileCount = tags.getOrDefault("fileCount", "0").toInt
    val files = (0 until fileCount).map(i => tags.get(s"file.$i")).filter(_ != null)

    import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
    val diagnosticCount = tags.getOrDefault("diagnostics.count", "0").toInt
    val diagnostics = (0 until diagnosticCount).flatMap { i =>
      val json = tags.get(s"diagnostic.$i")
      if (json != null) {
        try {
          Some(readFromString[TraceDiagnostic](json)(CompilationTrace.diagnosticCodec))
        } catch { case NonFatal(_) => None }
      } else None
    }

    val trace = CompilationTrace(
      project,
      files,
      diagnostics,
      artifacts,
      isNoOp,
      durationMs
    )

    try {
      if (!java.nio.file.Files.exists(traceFile.getParent.underlying)) {
        java.nio.file.Files.createDirectories(traceFile.getParent.underlying)
      }
      traceFile.getParent.underlying.synchronized {
        val projectTraceFile = traceFile.getParent.resolve(s"compilation-trace-${project}.json")
        val bytes = writeToArray(trace, WriterConfig.withIndentionStep(4))(CompilationTrace.codec)
        java.nio.file.Files.write(projectTraceFile.underlying, bytes)
        ()
      }
    } catch {
      case NonFatal(e) => e.printStackTrace()
    }
  }

  override def currentSpan: Option[Span] = None
  override def toIndependentTracer(
      name: String,
      traceProperties: TraceProperties,
      tags: (String, String)*
  ): BraveTracer =
    this
}
