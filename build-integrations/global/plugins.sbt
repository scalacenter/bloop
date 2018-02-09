import Defaults.sbtPluginExtra

val root = Option(System.getProperty("sbt.global.plugins"))
  .map(file(_).getAbsoluteFile)
  .getOrElse(sys.error("Missing `sbt.global.plugins`"))

unmanagedSourceDirectories in Compile ++= {
  val bloopBaseDir = root.getParentFile.getParentFile.getAbsoluteFile
  val integrationsMainDir = bloopBaseDir / "integrations"
  val pluginMainDir = integrationsMainDir / "sbt-bloop" / "src" / "main"
  List(
    root / "src" / "main" / "scala",
    integrationsMainDir / "core" / "src" / "main" / "scala",
    pluginMainDir / "scala",
    pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
  )
}

libraryDependencies := {
  val sbtVersion = (sbtBinaryVersion in pluginCrossBuild).value
  val scalaVersion = (scalaBinaryVersion in update).value
  // We dont' add sbt-coursier to all because of sbt-native-packager issues, sigh
  if (sbtVersion.startsWith("1.")) {
    List(
      sbtPluginExtra("com.lucidchart" % "sbt-scalafmt" % "1.15", sbtVersion, scalaVersion)
    )
  } else {
    List()
  }
}
