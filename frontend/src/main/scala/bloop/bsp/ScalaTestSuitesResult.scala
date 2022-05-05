package bloop.bsp

import scala.meta.jsonrpc.Endpoint

import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.ScalaTestClassesParams

import io.circe.Decoder
import io.circe.Encoder
import io.circe.derivation.deriveDecoder
import io.circe.derivation.deriveEncoder

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
