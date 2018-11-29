bloopConfigDir in Global := baseDirectory.value / "bloop-config"

import sbtcrossproject.{crossProject, CrossType}
lazy val `test-project` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .settings(
      name := "test-project",
      // %%% now include Scala Native. It applies to all selected platforms
      scalaVersion := "2.12.6",
      libraryDependencies += "com.lihaoyi" %%% "utest" % "0.6.6" % Test,
      libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test",
      testFrameworks += new TestFramework("utest.runner.Framework"),
      testOptions in Test ++= Seq(
        Tests.Exclude("hello.WritingTest" :: Nil),
        Tests.Exclude("hello.EternalUTest" :: Nil),
        Tests.Argument("-o"),
        Tests.Argument(TestFrameworks.JUnit, "-v", "+q", "-n")
      )
    )
    .jsConfigure(_.enablePlugins(ScalaJSJUnitPlugin).settings(
      // Make `%%%` whenever there is a version that supports 1.0.0-M5
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test",
      libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.14.0" % "test",
      libraryDependencies += "org.specs2" %% "specs2-core" % "4.3.3" % "test",
    ))
    .jvmSettings(
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test",
      libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.14.0" % "test",
      libraryDependencies += "org.specs2" %% "specs2-core" % "4.3.3" % "test",
    )


lazy val `test-project-js` = `test-project`.js.settings(
  // Should override default set above. Tested as part of ScalaJsToolchainSpec.
  bloopMainClass in Compile := Some("hello.DefaultApp")
)

lazy val `test-project-jvm` = `test-project`.jvm
