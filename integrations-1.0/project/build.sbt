val SbtSbt = build.BuildPlugin.SbtSbt
val GuardianFrontend = build.BuildPlugin.GuardianFrontend
val MiniBetterFiles = build.BuildPlugin.MiniBetterFiles
val WithResources = build.BuildPlugin.WithResources
val WithTests = build.BuildPlugin.WithTests

val dummy = project.dependsOn(SbtSbt, GuardianFrontend, MiniBetterFiles, WithResources, WithTests)
val scalafmtOnCompile = settingKey[Boolean]("...")

scalafmtOnCompile in Global := false

onLoad in Global := {
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in SbtSbt).value)
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in GuardianFrontend).value)
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in MiniBetterFiles).value)
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in WithResources).value)
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in WithTests).value)
  (onLoad in Global).value
}
