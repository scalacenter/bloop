val mvnVersion = "3.6.1"
val mvnPluginToolsVersion = "3.6.0"

// Create a proxy project instead of depending on plugin directly to work around https://github.com/sbt/sbt/issues/892
val `bloop-shaded-plugin` = project
  .settings(
    sbtPlugin := true,
    libraryDependencies += "ch.epfl.scala" %% "sbt-bloop-build-shaded-naked" % "1.0.0-SNAPSHOT"
  )

updateOptions := updateOptions.value.withLatestSnapshots(false)
val `bloop-build` = project
  .in(file("."))
  .dependsOn(`bloop-shaded-plugin`)
  .settings(
    exportJars := true,
    addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.0.0"),
    addSbtPlugin("ohnosequences" % "sbt-github-release" % "0.6.0"),
    addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.4"),
    addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.3"),
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3"),
    addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1+4-9d76569a"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.3.1"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.6.2"),
    addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.0.2"),
    addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.4.0"),
    // We need to add libdeps for the maven integration plugin to work
    libraryDependencies ++= List(
      "org.eclipse.jgit" % "org.eclipse.jgit" % "4.6.0.201612231935-r",
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-api" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-generators" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-annotations" % mvnPluginToolsVersion,
      "org.apache.maven" % "maven-core" % mvnVersion,
      "org.apache.maven" % "maven-plugin-api" % mvnVersion,
      "org.apache.maven" % "maven-model-builder" % mvnVersion,
      "commons-codec" % "commons-codec" % "1.11"
    ),
    // 5 hours to find that this had to be overridden because conflicted with sbt-pom-reader
    dependencyOverrides ++= List("org.apache.maven" % "maven-settings" % mvnVersion),
    // Add options to enable sbt-shading plugin sources
    libraryDependencies += {
      ("ch.epfl.scala" % "jarjar" % "1.7.2-patched")
        .exclude("org.apache.maven", "maven-plugin-api")
        .exclude("org.apache.ant", "ant")
    },
    unmanagedSourceDirectories in Compile ++= {
      val baseDir = baseDirectory.value.getParentFile
      List(
        baseDir / "sbt-shading" / "src" / "main" / "scala",
        baseDir / "sbt-shading" / "src" / "main" / "java"
      )
    }
  )

Keys.onLoad in Global := {
  val oldOnLoad = (Keys.onLoad in Global).value
  oldOnLoad.andThen { state =>
    val files = IO.listFiles(state.baseDir / "benchmark-bridge")
    if (!files.isEmpty) state
    else {
      throw new sbt.internal.util.MessageOnlyException(
        """
          |It looks like you didn't fully set up Bloop after cloning (git submodules are missing).
          |Read the contributing guide for more information: https://scalacenter.github.io/bloop/docs/contributing-guide#set-the-repository-up""".stripMargin
      )
    }
  }
}
