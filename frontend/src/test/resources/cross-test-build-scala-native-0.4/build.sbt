bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"

lazy val `test-project` =
  crossProject(NativePlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .settings(
      name := "test-project",
      scalaVersion := "2.13.17",
      mainClass in (Compile, run) := Some("hello.App")
    )

lazy val `test-project-native` = `test-project`.native.settings(
  // Should override default set above. Tested as part of ScalaNativeToolchainSpec.
  (Compile / run / bloopMainClass) := Some("hello.DefaultApp")
)

lazy val `test-project-jvm` = `test-project`.jvm.settings(
  (Compile / run / bloopMainClass) := Some("hello.App")
)
