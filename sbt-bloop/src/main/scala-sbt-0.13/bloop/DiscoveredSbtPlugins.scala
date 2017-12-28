package bloop

import sbt.{Compile, Def, PluginDiscovery, Setting}
import sbt.Keys.{compile, discoveredSbtPlugins, sbtPlugin}

object DiscoveredSbtPlugins {
  // Replace the implementation of `discoveredSbtPlugins` in sbt 0.13 by an implementation
  // that uses a dynamic task. This way, we don't need to compile to generate the resources,
  // except if the project is an sbt plugin.
  val settings: Seq[Setting[_]] = Seq(
    discoveredSbtPlugins := Def.taskDyn {
      if (sbtPlugin.value) Def.task { PluginDiscovery.discoverSourceAll(compile.value) } else
        Def.task { PluginDiscovery.emptyDiscoveredNames }
    }.value
  )
}
