bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"

import sbtcrossproject.{crossProject, CrossType}
val utestFramework = new TestFramework("utest.runner.Framework")
lazy val `test-project` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .settings(
      name := "test-project",
      // %%% now include Scala Native. It applies to all selected platforms
      scalaVersion := "2.11.12",
      scalacOptions += "-Ywarn-unused",
      mainClass in (Compile, run) := Some("hello.App"),
      libraryDependencies += "com.lihaoyi" %%% "utest" % "0.6.6" % Test,
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.0.4" % Test,
      libraryDependencies += "org.scalacheck" %%% "scalacheck" % "1.13.4" % Test,
      libraryDependencies += "org.specs2" %%% "specs2-core" % "4.7.0" % Test,
      libraryDependencies += "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      libraryDependencies += "org.pegdown" % "pegdown" % "1.6.0" % Test,
      testFrameworks += utestFramework,
      List(Compile, Test).flatMap(inConfig(_) {
        resourceGenerators += Def.task {
          val configName = configuration.value.name
          val fileName = s"generated-$configName-resource.txt"
          val out = resourceManaged.value / fileName
          IO.write(out, s"Content of $fileName")
          Keys.streams.value.log.info(s"Generated $out")
          Seq(out)
        }.taskValue
      }),
      testOptions in Test ++= Seq(
        Tests.Exclude("hello.WritingTest" :: Nil),
        Tests.Exclude("hello.EternalUTest" :: Nil),
        Tests.Argument("-o"),
        Tests.Argument(TestFrameworks.JUnit, "-v", "+q", "-n")
      )
    )
    .jsConfigure(_.enablePlugins(ScalaJSJUnitPlugin))

lazy val `test-project-js` = `test-project`.js.settings(
  // Should override default set above. Tested as part of ScalaJsToolchainSpec.
  bloopMainClass in (Compile, run) := Some("hello.DefaultApp")
)

lazy val `test-project-jvm` = `test-project`.jvm.settings(
  bloopMainClass in (Compile, run) := Some("hello.App"),
  unmanagedBase := baseDirectory.value / "custom_libraries"
)
