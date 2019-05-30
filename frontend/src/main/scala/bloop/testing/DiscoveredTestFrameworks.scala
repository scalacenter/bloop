package bloop.testing
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.exec.{JvmProcessForker}
import sbt.testing.Framework

sealed trait DiscoveredTestFrameworks {
  def frameworks: List[Framework]
}

object DiscoveredTestFrameworks {
  final case class Js(
      frameworks: List[Framework],
      closeResources: ScalaJsToolchain.CloseResources
  ) extends DiscoveredTestFrameworks

  final case class Jvm(
      frameworks: List[Framework],
      forker: JvmProcessForker,
      testLoader: ClassLoader
  ) extends DiscoveredTestFrameworks
}
