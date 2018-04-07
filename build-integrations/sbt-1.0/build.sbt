val SbtSbt = Integrations.SbtSbt
val GuardianFrontend = Integrations.GuardianFrontend
val MiniBetterFiles = Integrations.MiniBetterFiles
val WithResources = Integrations.WithResources
val WithTests = Integrations.WithTests
val integrations = List(SbtSbt, GuardianFrontend, MiniBetterFiles, WithResources, WithTests)

import bloop.build.integrations.PluginKeys
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-integrations-build",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "sbt" -> bloopConfigDir.in(SbtSbt).in(Compile).value,
        "frontend" -> bloopConfigDir.in(GuardianFrontend).in(Compile).value,
        "mini-better-files" -> bloopConfigDir.in(MiniBetterFiles).in(Compile).value,
        "with-resources" -> bloopConfigDir.in(WithResources).in(Compile).value,
        "with-tests" -> bloopConfigDir.in(WithTests).in(Compile).value
      )
    },
    cleanAllBuilds := {
      // Do it sequentially, there seems to be a race condition in windows
      Def.sequential(
        cleanAllBuilds,
        clean.in(SbtSbt),
        clean.in(GuardianFrontend),
        clean.in(MiniBetterFiles),
        clean.in(WithResources),
        clean.in(WithTests)
      )
    }
  )
