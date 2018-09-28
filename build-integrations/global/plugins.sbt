import Defaults.sbtPluginExtra


val root = Option(System.getProperty("sbt.global.plugins"))
  .map(file(_).getAbsoluteFile)
  .getOrElse(sys.error("Missing `sbt.global.plugins`"))

val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"
val circeCore = "io.circe" %% "circe-core" % "0.9.3"
val circeGeneric = "io.circe" %% "circe-generic" % "0.9.3"

unmanagedSourceDirectories in Compile ++= {
  val bloopBaseDir = root.getParentFile.getParentFile.getAbsoluteFile
  val integrationsMainDir = bloopBaseDir / "integrations"
  val pluginMainDir = integrationsMainDir / "sbt-bloop" / "src" / "main"
  val scalaDependentDir = {
    val scalaVersion = Keys.scalaBinaryVersion.value
    if (scalaVersion == "2.10")
      bloopBaseDir / "config" / "src" / "main" / s"scala-${Keys.scalaBinaryVersion.value}"
    else bloopBaseDir / "config" / "src" / "main" / s"scala-2.11-12"
  }

  List(
    root / "src" / "main" / "scala",
    bloopBaseDir / "config" / "src" / "main" / "scala",
    scalaDependentDir,
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
      sbtPluginExtra("com.lucidchart" % "sbt-scalafmt" % "1.15", sbtVersion, scalaVersion),
      sbtPluginExtra("com.eed3si9n" % "sbt-assembly" % "0.14.6", sbtVersion, scalaVersion),
      circeDerivation
    )
  } else {
    List(
      circeCore,
      circeGeneric,
      compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
    )
  }
}
