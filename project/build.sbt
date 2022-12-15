// Create a proxy project instead of depending on plugin directly to work around https://github.com/sbt/sbt/issues/892
val `bloop-shaded-plugin` = project
  .settings(
    sbtPlugin := true,
    libraryDependencies += "ch.epfl.scala" %% "sbt-bloop-build-naked" % "1.0.0-SNAPSHOT"
  )

updateOptions := updateOptions.value.withLatestSnapshots(false)
val `bloop-build` = project
  .in(file("."))
  .dependsOn(`bloop-shaded-plugin`)
  .settings(
    exportJars := true,
    addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.2.0"),
    addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.12.0"),
    addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1"),
    addSbtPlugin("ohnosequences" % "sbt-github-release" % "0.7.0"),
    addSbtPlugin("com.scalawilliam.esbeetee" % "sbt-vspp" % "0.4.11"),
    addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0"),
    addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0"),
    // Bumping this will causes issues. For now just lock it
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.7"),
    addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1"),
    addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1+4-9d76569a"),
    addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.6"),
    addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.8.1"),
    libraryDependencies ++= List(
      "org.eclipse.jgit" % "org.eclipse.jgit" % "5.13.1.202206130422-r",
      "org.eclipse.jgit" % "org.eclipse.jgit.ssh.jsch" % "5.13.1.202206130422-r",
      "commons-codec" % "commons-codec" % "1.15",
      ("ch.epfl.scala" % "jarjar" % "1.7.2-patched")
        .exclude("org.apache.ant", "ant")
    ),
    (Compile / unmanagedSourceDirectories) ++= {
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
