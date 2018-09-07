val Scalajs1 = Integrations.Scalajs1
val Atlas = Integrations.Atlas
val integrations = List(Scalajs1, Atlas)

import bloop.build.integrations.PluginKeys
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-integrations-build-2",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "scalajs-1" -> bloopConfigDir.in(Scalajs1).in(Compile).value,
        "atlas" -> bloopConfigDir.in(Atlas).in(Compile).value,
      )
    },
    cleanAllBuilds := {
      // Do it sequentially, there seems to be a race condition in windows
      Def.sequential(
        cleanAllBuilds,
        clean.in(Scalajs1),
        clean.in(Atlas),
      )
    }
  )
