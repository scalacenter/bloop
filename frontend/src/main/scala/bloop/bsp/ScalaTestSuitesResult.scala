package bloop.bsp

import io.circe.derivation.JsonCodec
import ch.epfl.scala.bsp.BuildTargetIdentifier
import io.circe.derivation.deriveDecoder
import io.circe.derivation.deriveEncoder
import io.circe.Decoder
import io.circe.Encoder
import scala.meta.jsonrpc.Endpoint
import ch.epfl.scala.bsp.ScalaTestClassesParams

object ScalaTestClasses {
  val endpoint = new Endpoint[ScalaTestClassesParams, ScalaTestClassesResult]("buildTarget/scalaTestClasses")
}

final case class ScalaTestClassesResult(
    items: List[ScalaTestClassesItem]
)

object ScalaTestClassesResult {
  implicit val decoder: Decoder[ScalaTestClassesResult] = deriveDecoder
  implicit val encoder: Encoder[ScalaTestClassesResult] = deriveEncoder
}

final case class ScalaTestClassesItem(
    target: BuildTargetIdentifier,
    // Fully qualified names of test classes
    classes: List[String],
    // Name of the sbt's test framework
    framework: Option[String]
)
object ScalaTestClassesItem {
  implicit val decoder: Decoder[ScalaTestClassesItem] = deriveDecoder
  implicit val encoder: Encoder[ScalaTestClassesItem] = deriveEncoder
}
