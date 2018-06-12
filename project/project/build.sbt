libraryDependencies += "io.circe" %% "circe-derivation" % "0.9.0-M3"
// Let's add our sbt plugin to the sbt too ;)
unmanagedSourceDirectories in Compile ++= {
  val baseDir = baseDirectory.value.getParentFile.getParentFile
  val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
  List(
    baseDir / "config" / "src" / "main" / "scala",
    baseDir / "config" / "src" / "main" / s"scala-${Keys.scalaBinaryVersion.value}",
    pluginMainDir / "scala",
    pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
  )
}
