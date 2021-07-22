package bloop.data

case class JavaSemanticdbSettings(semanticDBVersion: String)
case class ScalaSemanticdbSettings(semanticDBVersion: String, supportedScalaVersions: List[String])
case class SemanticdbSettings(
    javaSemanticdbSettings: Option[JavaSemanticdbSettings],
    scalaSemanticdbSettings: Option[ScalaSemanticdbSettings]
)
