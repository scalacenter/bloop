package bloop.bsp

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/**
 * Below datatypes are based on https://github.com/build-server-protocol/build-server-protocol/issues/249#issuecomment-983435766
 */
case class ScalaTestSuites(
    suites: List[ScalaTestSuiteSelection],
    jvmOptions: List[String],
    environmentVariables: List[String]
) {
  def classNames: List[String] = suites.map(_.className)
}

object ScalaTestSuites {
  implicit val jsonCodec: JsonValueCodec[ScalaTestSuites] =
    JsonCodecMaker.makeWithRequiredCollectionFields
  val empty: ScalaTestSuites = ScalaTestSuites(Nil, Nil, Nil)

  def apply(classes: List[String]): ScalaTestSuites = ScalaTestSuites(
    classes.map(className => ScalaTestSuiteSelection(className, Nil)),
    Nil,
    Nil
  )

  def forSuiteSelection(classes: List[ScalaTestSuiteSelection]): ScalaTestSuites = ScalaTestSuites(
    classes,
    Nil,
    Nil
  )
}

case class ScalaTestSuiteSelection(
    className: String,
    tests: List[String]
)
object ScalaTestSuiteSelection {
  implicit val jsonCodec: JsonValueCodec[ScalaTestSuiteSelection] =
    JsonCodecMaker.makeWithRequiredCollectionFields
}
