addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.1")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.13.1")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("ohnosequences" % "sbt-github-release" % "0.7.0")
addSbtPlugin("com.scalawilliam.esbeetee" % "sbt-vspp" % "0.4.11")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
// Bumping this will causes issues. The benchmark bridge
// needs to be updated in order for us to bump to 0.4.x.
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.7")
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.12")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.7")
addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.16")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.0")

updateOptions := updateOptions.value.withLatestSnapshots(false)
libraryDependencies ++= List(
  "org.eclipse.jgit" % "org.eclipse.jgit" % "6.6.0.202305301015-r",
  "org.eclipse.jgit" % "org.eclipse.jgit.ssh.jsch" % "6.6.0.202305301015-r",
  "commons-codec" % "commons-codec" % "1.15",
  ("ch.epfl.scala" % "jarjar" % "1.7.2-patched")
    .exclude("org.apache.ant", "ant")
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
