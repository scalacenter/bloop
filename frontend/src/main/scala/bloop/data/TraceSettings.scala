package bloop.data

import bloop.tracing.TraceProperties

case class TraceSettings(
    serverUrl: Option[String],
    debugTracing: Option[Boolean],
    verbose: Option[Boolean],
    localServiceName: Option[String],
    traceStartAnnotation: Option[String],
    traceEndAnnotation: Option[String],
    enabled: Option[Boolean],
    compilationTrace: Option[Boolean]
)

object TraceSettings {
  def toProperties(settings: TraceSettings): TraceProperties = {
    val default = TraceProperties.default
    TraceProperties(
      settings.serverUrl.getOrElse(default.serverUrl),
      settings.debugTracing.getOrElse(default.debugTracing),
      settings.verbose.getOrElse(default.verbose),
      settings.localServiceName.getOrElse(default.localServiceName),
      settings.traceStartAnnotation.orElse(default.traceStartAnnotation),
      settings.traceEndAnnotation.orElse(default.traceEndAnnotation),
      settings.enabled.getOrElse(default.enabled),
      settings.compilationTrace.getOrElse(default.compilationTrace)
    )
  }

  def fromProperties(properties: TraceProperties): TraceSettings = {
    TraceSettings(
      serverUrl = Some(properties.serverUrl),
      debugTracing = Some(properties.debugTracing),
      verbose = Some(properties.verbose),
      localServiceName = Some(properties.localServiceName),
      traceStartAnnotation = properties.traceStartAnnotation,
      traceEndAnnotation = properties.traceEndAnnotation,
      enabled = Some(properties.enabled),
      compilationTrace = Some(properties.compilationTrace)
    )
  }
}
