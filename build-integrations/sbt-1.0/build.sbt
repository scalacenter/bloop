val SbtSbt = RootProject(
  uri("git://github.com/scalacenter/sbt.git#c84db3a3969e269aeed0e9d9f2f384a0b029b82c"))
val GuardianFrontend = RootProject(
  uri("git://github.com/scalacenter/frontend.git#fd8da1929d8a3bd39ca6027ffba6c0850e036ce3"))
val MiniBetterFiles = RootProject(
  uri(
    "git://github.com/scalacenter/mini-better-files.git#0ed848993a2fd5a36e4366b5efb9c68dce958fc2"))
val WithResources = RootProject(
  uri("git://github.com/scalacenter/with-resources.git#7529b2c3ac455cbb1889d4791c4e0d4957e29306"))
val WithTests = RootProject(
  uri("git://github.com/scalacenter/with-tests.git#7a0c7f7d38efd53ca9ec3a347df3638932bd619e"))

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
