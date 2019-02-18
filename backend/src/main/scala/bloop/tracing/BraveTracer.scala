package bloop.tracing

import brave.{Span, Tracer}
import monix.eval.Task

final class BraveTracer private (
    tracer: Tracer,
    currentSpan: Span,
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
    finally newTracer.terminate()
  }

  def traceTask[T](name: String, tags: (String, String)*)(
      thunk: BraveTracer => Task[T]
  ): Task[T] = {
    val tracer = startNewChildTracer(name, tags: _*)
    thunk(tracer).materialize.map { value =>
      tracer.terminate()
      value
    }.dematerialize
  }

  private var terminated: Boolean = false
  def terminate(): Unit = synchronized {
    if (terminated) ()
    else {
      closeCurrentSpan()
      terminated = true
    }
  }
}

object BraveTracer {

  def apply(topLevelTaskName: String): BraveTracer = {
    import brave._
    import zipkin2.reporter.AsyncReporter
    import zipkin2.reporter.urlconnection.URLConnectionSender
    val zipkinServerUrl = Option(System.getProperty("zipkin.server.url")).getOrElse(
      "http://127.0.0.1:9411/api/v2/spans"
    )

    import java.util.concurrent.TimeUnit
    val sender = URLConnectionSender.create(zipkinServerUrl)
    val spanReporter = AsyncReporter.builder(sender).messageTimeout(0, TimeUnit.SECONDS).build()
    val tracing =
      Tracing.newBuilder().localServiceName("bloop").spanReporter(spanReporter).build()
    val tracer = tracing.tracer()
    val rootSpan = tracer.newTrace().name(topLevelTaskName).start()
    val closeEverything = () => {
      var closedReporter: Boolean = false
      var closedSender: Boolean = false
      try {
        rootSpan.finish()
        tracing.close()
        spanReporter.flush()
        spanReporter.close()
        closedReporter = true
        sender.close()
        closedSender = true
      } catch {
        case t: Throwable =>
          if (!closedReporter) spanReporter.close()
          if (!closedSender) sender.close()
      }
    }
    new BraveTracer(tracer, rootSpan, closeEverything)
  }
}
