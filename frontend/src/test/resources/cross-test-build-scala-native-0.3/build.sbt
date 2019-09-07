bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"

lazy val `test-project` =
  crossProject(NativePlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .settings(
      name := "test-project",
      scalaVersion := "2.11.12",
      scalacOptions += "-Ywarn-unused",
      mainClass in (Compile, run) := Some("hello.App")
    )

lazy val `test-project-native` = `test-project`.native.settings(
  // Should override default set above. Tested as part of ScalaNativeToolchainSpec.
  bloopMainClass in (Compile, run) := Some("hello.DefaultApp")
)

lazy val `test-project-jvm` = `test-project`.jvm.settings(
  bloopMainClass in (Compile, run) := Some("hello.App")
)
