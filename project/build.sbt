addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.2.0")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.13.0")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
// Bumping this will causes issues. The benchmark bridge
// needs to be updated in order for us to bump to 0.4.x.
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.7")
addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.10.4")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.9")

libraryDependencies ++= List(
  "org.eclipse.jgit" % "org.eclipse.jgit" % "6.5.0.202303070854-r",
  "org.eclipse.jgit" % "org.eclipse.jgit.ssh.jsch" % "6.5.0.202303070854-r",
  "commons-codec" % "commons-codec" % "1.15"
)
