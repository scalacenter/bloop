exportJars := true
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.6.0")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.0.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.4")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.3.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.6.3")
addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.9")

libraryDependencies ++= List(
  "org.eclipse.jgit" % "org.eclipse.jgit" % "5.12.0.202106070339-r",
  "org.eclipse.jgit" % "org.eclipse.jgit.ssh.jsch" % "5.12.0.202106070339-r",
  "commons-codec" % "commons-codec" % "1.11"
)
