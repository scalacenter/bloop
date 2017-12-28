package bloop

import sbt.Setting

object DiscoveredSbtPlugins {
  // The implementation of `discoveredSbtPlugins` in sbt 1.0 is correct.
  // We don't need to replace it.
  val settings: Seq[Setting[_]] = Seq.empty
}
