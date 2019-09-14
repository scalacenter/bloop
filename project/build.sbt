val mvnVersion = "3.6.1"
val mvnPluginToolsVersion = "3.6.0"
val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"

val `bloop-build` = project
  .in(file("."))
  .settings(
    scalaVersion := "2.12.4",
    addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.1.0"),
    addSbtPlugin("ohnosequences" % "sbt-github-release" % "0.6.0"),
    addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.14"),
    addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.3"),
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3"),
    addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.3.1"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.6.2"),
    addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.1.0-M13-2"),
    addSbtPlugin("org.scalameta" % "sbt-mdoc" % "1.2.10"),
    addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1"),
    libraryDependencies += { "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value },
    // Let's add our sbt plugin to the sbt too ;)
    unmanagedSourceDirectories in Compile ++= {
      val baseDir = baseDirectory.value.getParentFile
      val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
      List(
        baseDir / "config" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / "scala-2.11-12",
        pluginMainDir / "scala",
        pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
      )
    },
    // We need to add libdeps for the maven integration plugin to work
    libraryDependencies ++= List(
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-api" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-generators" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-annotations" % mvnPluginToolsVersion,
      "org.apache.maven" % "maven-core" % mvnVersion,
      "org.apache.maven" % "maven-plugin-api" % mvnVersion,
      "org.apache.maven" % "maven-model-builder" % mvnVersion,
      "commons-codec" % "commons-codec" % "1.11"
    ),
    libraryDependencies ++= List(circeDerivation),
    // 5 hours to find that this had to be overridden because conflicted with sbt-pom-reader
    dependencyOverrides ++= List("org.apache.maven" % "maven-settings" % mvnVersion)
  )

Keys.onLoad in Global := {
  val oldOnLoad = (Keys.onLoad in Global).value
  oldOnLoad.andThen { state =>
    val files = IO.listFiles(state.baseDir / "benchmark-bridge")
    if (files.isEmpty) {
      throw new sbt.internal.util.MessageOnlyException(
        """
          |It looks like you didn't fully set up Bloop after cloning (git submodules are missing).
          |Read the contributing guide for more information: https://scalacenter.github.io/bloop/docs/contributing-guide#set-the-repository-up""".stripMargin
      )
    } else state
  }
}
