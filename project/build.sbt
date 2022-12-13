exportJars := true
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.6.0")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.4")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.2")
addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.9")

libraryDependencies ++= List(
  "org.eclipse.jgit" % "org.eclipse.jgit" % "5.12.0.202106070339-r",
  "org.eclipse.jgit" % "org.eclipse.jgit.ssh.jsch" % "5.12.0.202106070339-r",
  "commons-codec" % "commons-codec" % "1.15"
)
