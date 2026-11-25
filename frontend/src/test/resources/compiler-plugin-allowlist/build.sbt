bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"
import _root_.sbtcrossproject.CrossPlugin.autoImport.{crossProject => crossProjects}
import sbt.Value

scalaVersion in ThisBuild := "2.12.21"

lazy val `bloop-test-plugin` = project
  .settings(
    libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    publishArtifact in Compile := false,
    bloopGenerate in Compile := Value(None),
    bloopGenerate in Test := Value(None)
  )

val silencerVersion = "1.7.19"
val derivingVersion = "1.0.0"
// Don't add -Ycache-plugin-class-loader:last-modified, the point is that bloop will do it automatically
lazy val allowlist = crossProjects(JVMPlatform, JSPlatform)
  .settings(
    wartremoverErrors ++= Warts.unsafe,
    addCompilerPlugin(scalafixSemanticdb),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
    libraryDependencies ++= Seq(
      compilerPlugin(
        "com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full
      ),
      "com.github.ghik" % "silencer-lib" % silencerVersion % Provided cross CrossVersion.full
    ),
    addCompilerPlugin(
      "com.sksamuel.scapegoat" % "scalac-scapegoat-plugin" % "3.4.1" cross CrossVersion.full
    ),
    libraryDependencies += "com.lihaoyi" % "acyclic" % "0.3.21" % "provided" cross CrossVersion.full,
    autoCompilerPlugins := true,
    addCompilerPlugin("com.lihaoyi" % "acyclic" % "0.3.21" cross CrossVersion.full),
    scalacOptions in Compile += "-P:scapegoat:dataDir:./target/scapegoat",
    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.4" cross CrossVersion.full),
    addCompilerPlugin("ch.epfl.scala" %% "classpath-shrinker" % "0.1.1"),
    addCompilerPlugin("ch.epfl.scala" % "scalac-profiling" % "1.2.2" cross CrossVersion.full),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    libraryDependencies ++= List(
      "org.scalaz" %% "deriving-macro" % derivingVersion,
      compilerPlugin("org.scalaz" %% "deriving-plugin" % derivingVersion)
    ),
    // Now let's add our test plugin to the allowlist
    scalacOptions in Compile ++= {
      val jar = (Keys.`package` in (`bloop-test-plugin`, Compile)).value
      val addPlugin = "-Xplugin:" + jar.getAbsolutePath
      Seq(addPlugin)
    }
  )

// This is just a project to play with when making changes in this build
lazy val sandbox = project
  .settings(
    // Now let's add our test plugin to the allowlist
    scalacOptions in Compile ++= {
      val jar = (Keys.`package` in (`bloop-test-plugin`, Compile)).value
      val addPlugin = "-Xplugin:" + jar.getAbsolutePath
      Seq(addPlugin, "-Ycache-plugin-class-loader:last-modified")
    },
    scalacOptions in Compile := {
      val (pluginOptions, rest) =
        (scalacOptions in Compile).value.partition(_.contains("-Xplugin"))
      pluginOptions.filter(_.contains("bloop-test-plugin")) ++ rest
    }
  )
