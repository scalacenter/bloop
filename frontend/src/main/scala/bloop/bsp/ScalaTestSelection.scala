package bloop.bsp

import io.circe.derivation.JsonCodec
import io.circe.derivation.deriveDecoder
import io.circe.derivation.deriveEncoder
import io.circe.Decoder
import io.circe.Encoder

/**
  * Below datatypes are based on https://github.com/build-server-protocol/build-server-protocol/issues/249#issuecomment-983435766
  */

case class ScalaTestSelection(
    jvmOptions: List[String],
    classes: List[ScalaTestSuiteSelection],
    env: Map[String, String],
)

object ScalaTestSelection {
  implicit val decoder: Decoder[ScalaTestSelection] = deriveDecoder
  implicit val encoder: Encoder[ScalaTestSelection] = deriveEncoder
}

case class ScalaTestSuiteSelection(
    className: String,
    tests: List[String]
)
object ScalaTestSuiteSelection {
  implicit val decoder: Decoder[ScalaTestSuiteSelection] = deriveDecoder
  implicit val encoder: Encoder[ScalaTestSuiteSelection] = deriveEncoder
}
