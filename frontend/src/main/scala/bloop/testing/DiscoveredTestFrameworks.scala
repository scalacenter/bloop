package bloop.testing

import bloop.engine.tasks.ScalaJsToolchain
import bloop.exec.Forker
import sbt.testing.Framework

sealed trait DiscoveredTestFrameworks {
  def frameworks: List[Framework]
}

object DiscoveredTestFrameworks {
  case class Js(frameworks: List[Framework], dispose: ScalaJsToolchain.Dispose)
      extends DiscoveredTestFrameworks

  case class Jvm(frameworks: List[Framework], forker: Forker, testLoader: ClassLoader)
      extends DiscoveredTestFrameworks
}
