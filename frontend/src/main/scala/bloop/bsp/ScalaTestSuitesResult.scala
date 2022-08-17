package bloop.bsp

import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.ScalaTestClassesParams

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import jsonrpc4s.Endpoint

object ScalaTestClasses {
  val endpoint =
    new Endpoint[ScalaTestClassesParams, ScalaTestClassesResult]("buildTarget/scalaTestClasses")
}

final case class ScalaTestClassesResult(
    items: List[ScalaTestClassesItem]
)

object ScalaTestClassesResult {
  implicit val jsonCodec: JsonValueCodec[ScalaTestClassesResult] =
    JsonCodecMaker.makeWithRequiredCollectionFields
}

final case class ScalaTestClassesItem(
    target: BuildTargetIdentifier,
    // Fully qualified names of test classes
    classes: List[String],
    // Name of the sbt's test framework
    framework: Option[String]
)
object ScalaTestClassesItem {
  implicit val jsonCodec: JsonValueCodec[ScalaTestClassesItem] =
    JsonCodecMaker.makeWithRequiredCollectionFields
}
