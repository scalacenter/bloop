libraryDependencies += "io.circe" %% "circe-derivation" % "0.9.0-M3"
libraryDependencies += "ch.epfl.scala" % "bsp4j" % "2.0.0-M4"
// Let's add our sbt plugin to the sbt too ;)
unmanagedSourceDirectories in Compile ++= {
  val baseDir = baseDirectory.value.getParentFile.getParentFile
  val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
  val bspClientDir = baseDir / "integrations" / "bsp4j-client" / "src" / "main"
  List(
    baseDir / "config" / "src" / "main" / "scala",
    baseDir / "config" / "src" / "main" / "scala-2.11-12",
    bspClientDir / "scala",
    pluginMainDir / "scala",
    pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
  )
}
