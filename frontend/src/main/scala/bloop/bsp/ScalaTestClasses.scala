package bloop.bsp

import io.circe.derivation.JsonCodec
import io.circe.derivation.deriveDecoder
import io.circe.derivation.deriveEncoder
import io.circe.Decoder
import io.circe.Encoder

/**
  * Below datatypes are based on https://github.com/build-server-protocol/build-server-protocol/issues/249#issuecomment-983435766
  */
case class ScalaTestClasses(
    classes: List[ScalaTestSuiteSelection],
    jvmOptions: List[String],
    env: Map[String, String],
) {
  def classNames: List[String] = classes.map(_.className)
}

object ScalaTestClasses {
  implicit val decoder: Decoder[ScalaTestClasses] = deriveDecoder
  implicit val encoder: Encoder[ScalaTestClasses] = deriveEncoder
  val empty: ScalaTestClasses = ScalaTestClasses(Nil, Nil, Map.empty)

  def apply(classes: List[String]): ScalaTestClasses = ScalaTestClasses(
    classes.map(className => ScalaTestSuiteSelection(className, Nil)), 
    Nil,
    Map.empty
  )

  def forSuiteSelection(classes: List[ScalaTestSuiteSelection]): ScalaTestClasses = ScalaTestClasses(
    classes,
    Nil,
    Map.empty
  )
}

case class ScalaTestSuiteSelection(
    className: String,
    tests: List[String]
)
object ScalaTestSuiteSelection {
  implicit val decoder: Decoder[ScalaTestSuiteSelection] = deriveDecoder
  implicit val encoder: Encoder[ScalaTestSuiteSelection] = deriveEncoder
}
