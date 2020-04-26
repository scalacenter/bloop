bloopConfigDir in Global := baseDirectory.value / "bloop-config"

import sbtcrossproject.{crossProject, CrossType}
lazy val `test-project` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .settings(
      name := "test-project",
      // %%% now include Scala Native. It applies to all selected platforms
      scalaVersion := "2.13.1",
      libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test,
      libraryDependencies += "com.lihaoyi" %%% "utest" % "0.7.4" % Test,
      testFrameworks += new TestFramework("utest.runner.Framework"),
      testOptions in Test ++= Seq(
        Tests.Exclude("hello.WritingTest" :: Nil),
        Tests.Exclude("hello.EternalUTest" :: Nil),
        Tests.Argument("-o"),
        Tests.Argument(TestFrameworks.JUnit, "-v", "+q", "-n")
      )
    )
    .jsConfigure(
      _.enablePlugins(ScalaJSJUnitPlugin).settings(
        libraryDependencies += "org.scalatest" %%% "scalatest" % "3.1.1" % Test,
        libraryDependencies += "org.scalacheck" %%% "scalacheck" % "1.14.3" % Test,
        libraryDependencies += "org.specs2" %%% "specs2-core" % "4.9.2" % Test
      ))
    .jvmSettings(
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.1" % Test,
      libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.14.3" % Test,
      libraryDependencies += "org.specs2" %% "specs2-core" % "4.9.2" % Test,
    )

lazy val `test-project-js` = `test-project`.js
lazy val `test-project-jvm` = `test-project`.jvm
