package bloop.testing
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.exec.Forker
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
      forker: Forker,
      testLoader: ClassLoader
  ) extends DiscoveredTestFrameworks
}
