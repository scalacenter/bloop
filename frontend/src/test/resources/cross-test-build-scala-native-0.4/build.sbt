bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"

lazy val `test-project` =
  crossProject(NativePlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .settings(
      name := "test-project",
      scalaVersion := "2.13.18",
      mainClass in (Compile, run) := Some("hello.App")
    )

lazy val `test-project-native` = `test-project`.native.settings(
  // Should override default set above. Tested as part of ScalaNativeToolchainSpec.
  (Compile / run / bloopMainClass) := Some("hello.DefaultApp"),
  // With recent MSVC (14.52+) both the UCRT (libucrt.lib) and the static C++ runtime
  // (libcpmt.lib) define `_Sinh` and `_FSinh`, which makes lld-link fail with duplicate
  // symbols. Scala Native 0.4 only ignores those duplicates when LTO is enabled.
  nativeLinkingOptions ++= {
    if (scala.util.Properties.isWin) Seq("-Wl,/force:multiple") else Nil
  }
)

lazy val `test-project-jvm` = `test-project`.jvm.settings(
  (Compile / run / bloopMainClass) := Some("hello.App")
)
