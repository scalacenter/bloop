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
    PluginKeys.enableIndexCreation := true,
    PluginKeys.integrationIndex := {
      Map(
        "sbt" -> bloopConfigDir.in(SbtSbt).in(Compile).value,
        "frontend" -> bloopConfigDir.in(GuardianFrontend).in(Compile).value,
        "mini-better-files" -> bloopConfigDir.in(MiniBetterFiles).in(Compile).value,
        "with-resources" -> bloopConfigDir.in(WithResources).in(Compile).value,
        "with-tests" -> bloopConfigDir.in(WithTests).in(Compile).value
      )
    }
  )
