package bloop.tracing

import scala.util.Properties

case class TraceProperties(
    zipkinServerUrl: Option[String],
    debug: Option[Boolean],
    verbose: Option[Boolean],
    localServiceName: Option[String],
    traceStartAnnotation: Option[String],
    traceEndAnnotation: Option[String]
)
object TraceProperties {

  sealed abstract case class Property[A](key: String) {
    def getOrGlobal(properties: TraceProperties): A
  }
  object ZipkinServerUrl extends Property[String]("zipkin.server.url") {
    override def getOrGlobal(properties: TraceProperties): String =
      properties.zipkinServerUrl.getOrElse(Global.zipkinServerUrl)
  }
  object Debug extends Property[Boolean]("bloop.trace.debug") {
    override def getOrGlobal(properties: TraceProperties): Boolean =
      properties.debug.getOrElse(Global.debug)
  }
  object Verbose extends Property[Boolean]("bloop.trace.verbose") {
    override def getOrGlobal(properties: TraceProperties): Boolean =
      properties.verbose.getOrElse(Global.verbose)
  }
  object LocalServiceName extends Property[String]("bloop.trace.localServiceName") {
    override def getOrGlobal(properties: TraceProperties): String =
      properties.localServiceName.getOrElse(Global.localServiceName)
  }
  object TraceStartAnnotation extends Property[Option[String]]("bloop.trace.traceStartAnnotation") {
    override def getOrGlobal(properties: TraceProperties): Option[String] =
      properties.traceStartAnnotation.orElse(Global.traceStartAnnotation)
  }
  object TraceEndAnnotation extends Property[Option[String]]("bloop.trace.traceEndAnnotation") {
    override def getOrGlobal(properties: TraceProperties): Option[String] =
      properties.traceEndAnnotation.orElse(Global.traceEndAnnotation)
  }

  val default: TraceProperties = TraceProperties(
    Some("http://127.0.0.1:9411/api/v2/spans"),
    Some(false),
    Some(false),
    Some("bloop"),
    None,
    None
  )

  object Global {

    val zipkinServerUrl: String =
      Properties.propOrElse(TraceProperties.ZipkinServerUrl.key, default.zipkinServerUrl.get)

    val debug: Boolean = Properties.propOrFalse(TraceProperties.Debug.key)

    val verbose: Boolean = Properties.propOrFalse(TraceProperties.Verbose.key)

    val localServiceName: String =
      Properties.propOrElse(TraceProperties.LocalServiceName.key, default.localServiceName.get)

    val traceStartAnnotation: Option[String] =
      Properties.propOrNone(TraceProperties.TraceStartAnnotation.key)

    val traceEndAnnotation: Option[String] =
      Properties.propOrNone(TraceProperties.TraceEndAnnotation.key)

    val properties: TraceProperties = TraceProperties(
      Some(zipkinServerUrl),
      Some(debug),
      Some(verbose),
      Some(localServiceName),
      traceStartAnnotation,
      traceEndAnnotation
    )
  }
}
