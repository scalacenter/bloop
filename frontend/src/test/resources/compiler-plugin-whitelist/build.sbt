bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"
import _root_.sbtcrossproject.CrossPlugin.autoImport.{crossProject => crossProjects}

scalaVersion in ThisBuild := "2.12.8"

lazy val `bloop-test-plugin` = project
  .settings(
    libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    publishArtifact in Compile := false
  )

val silencerVersion = "1.3.1"
val derivingVersion = "1.0.0"
// Don't add -Ycache-plugin-class-loader:last-modified, the point is that bloop will do it automatically
lazy val whitelist = crossProjects(JVMPlatform, JSPlatform)
  .settings(
    wartremoverErrors ++= Warts.unsafe,
    addCompilerPlugin(scalafixSemanticdb),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
    libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVersion),
      "com.github.ghik" %% "silencer-lib" % silencerVersion % Provided
    ),
    addCompilerPlugin("com.sksamuel.scapegoat" %% "scalac-scapegoat-plugin" % "1.3.8"),
    // Required to add as normal libdep too to work around https://github.com/sksamuel/scapegoat/issues/98
    libraryDependencies += "com.sksamuel.scapegoat" %% "scalac-scapegoat-plugin" % "1.3.8",
    libraryDependencies += "com.lihaoyi" %% "acyclic" % "0.1.7" % "provided",
    autoCompilerPlugins := true,
    addCompilerPlugin("com.lihaoyi" %% "acyclic" % "0.1.7"),
    scalacOptions in Compile += "-P:scapegoat:dataDir:./target/scapegoat",
    addCompilerPlugin("org.scoverage" %% "scalac-scoverage-plugin" % "1.3.1"),
    libraryDependencies += "org.scoverage" %%% "scalac-scoverage-runtime" % "1.3.1",
    scalacOptions in Compile += {
      val scapegoatDataDir = (bloopConfigDir in Global).value / "whitelistJS" / "scapegoat"
      java.nio.file.Files.createDirectories(scapegoatDataDir.toPath)
      s"-P:scoverage:dataDir:${scapegoatDataDir}"
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8"),
    resolvers += Resolver.bintrayRepo("scalacenter", "releases"),
    addCompilerPlugin("ch.epfl.scala" %% "classpath-shrinker" % "0.1.1"),
    addCompilerPlugin("ch.epfl.scala" %% "scalac-profiling" % "1.0.0"),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0-M4"),
    addCompilerPlugin("io.tryp" % "splain" % "0.3.5" cross CrossVersion.patch),
    libraryDependencies ++= List(
      "org.scalaz" %% "deriving-macro" % derivingVersion,
      compilerPlugin("org.scalaz" %% "deriving-plugin" % derivingVersion)
    ),
    // Now let's add our test plugin to the whitelist
    scalacOptions in Compile ++= {
      val jar = (Keys.`package` in (`bloop-test-plugin`, Compile)).value
      val addPlugin = "-Xplugin:" + jar.getAbsolutePath
      Seq(addPlugin)
    }
  )

// This is just a project to play with when making changes in this build
lazy val sandbox = project
  .settings(
    // Now let's add our test plugin to the whitelist
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
