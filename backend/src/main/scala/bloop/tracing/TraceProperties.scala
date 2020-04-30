package bloop.tracing

import scala.util.Properties

case class TraceProperties(
    serverUrl: String,
    debug: Boolean,
    verbose: Boolean,
    localServiceName: String,
    traceStartAnnotation: Option[String],
    traceEndAnnotation: Option[String]
)

object TraceProperties {
  val default: TraceProperties = {
    val debug = Properties.propOrFalse("bloop.trace.debug")
    val verbose = Properties.propOrFalse("bloop.trace.verbose")
    val localServiceName = Properties.propOrElse("bloop.trace.localServiceName", "bloop")
    val traceStartAnnotation = Properties.propOrNone("bloop.trace.traceStartAnnotation")
    val traceEndAnnotation = Properties.propOrNone("bloop.trace.traceEndAnnotation")

    val traceServerUrl = Properties.propOrElse(
      "zipkin.server.url",
      Properties.propOrElse("bloop.trace.server.url", "http://127.0.0.1:9411/api/v2/spans")
    )

    TraceProperties(
      traceServerUrl,
      debug,
      verbose,
      localServiceName,
      traceStartAnnotation,
      traceEndAnnotation
    )
  }
}
