package bloop.tracing

import scala.util.Properties

case class TraceProperties(
    serverUrl: String,
    debugTracing: Boolean,
    verbose: Boolean,
    localServiceName: String,
    traceStartAnnotation: Option[String],
    traceEndAnnotation: Option[String]
)

object TraceProperties {
  val default: TraceProperties = {
    val verbose = Properties.propOrFalse("bloop.tracing.verbose")
    val debugTracing = Properties.propOrFalse("bloop.tracing.debugTracing")
    val localServiceName = Properties.propOrElse("bloop.tracing.localServiceName", "bloop")
    val traceStartAnnotation = Properties.propOrNone("bloop.tracing.traceStartAnnotation")
    val traceEndAnnotation = Properties.propOrNone("bloop.tracing.traceEndAnnotation")

    val traceServerUrl = Properties.propOrElse(
      "zipkin.server.url",
      Properties.propOrElse("bloop.tracing.server.url", "http://127.0.0.1:9411/api/v2/spans")
    )

    TraceProperties(
      traceServerUrl,
      debugTracing,
      verbose,
      localServiceName,
      traceStartAnnotation,
      traceEndAnnotation
    )
  }
}
