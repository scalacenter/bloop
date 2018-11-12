bloopConfigDir in Global := baseDirectory.value / "bloop-config"

import sbtcrossproject.{crossProject, CrossType}
lazy val `test-project` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .settings(
      name := "test-project",
      // %%% now include Scala Native. It applies to all selected platforms
      scalaVersion := "2.11.12",
      scalacOptions += "-Ywarn-unused",
      libraryDependencies += "com.lihaoyi" %%% "utest" % "0.6.6" % Test,
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.0.4" % "test",
      libraryDependencies += "org.scalacheck" %%% "scalacheck" % "1.13.4" % "test",
      libraryDependencies += "org.specs2" %%% "specs2-core" % "4.3.3" % "test",
      libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test",
      testFrameworks += new TestFramework("utest.runner.Framework"),
      testOptions in Test ++= Seq(
        Tests.Exclude("hello.WritingTest" :: Nil),
        Tests.Exclude("hello.EternalUTest" :: Nil),
        Tests.Argument("-o"),
        Tests.Argument(TestFrameworks.JUnit, "-v", "+q", "-n")
      ),
    )
    .jsConfigure(_.enablePlugins(ScalaJSJUnitPlugin))


lazy val `test-project-js` = `test-project`.js
lazy val `test-project-jvm` = `test-project`.jvm
