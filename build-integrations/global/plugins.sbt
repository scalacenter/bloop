import Defaults.sbtPluginExtra

val root = Option(System.getProperty("sbt.global.plugins"))
  .map(file(_).getAbsoluteFile)
  .getOrElse(sys.error("Missing `sbt.global.plugins`"))

val typesafeConfig = "com.typesafe" % "config" % "1.3.2"
val metaconfigCore = "com.geirsson" %% "metaconfig-core" % "0.6.0"
val metaconfigConfig = "com.geirsson" %% "metaconfig-typesafe-config" % "0.6.0"
val metaconfigDocs = "com.geirsson" %% "metaconfig-docs" % "0.6.0"
val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"
val circeCore = "io.circe" %% "circe-core" % "0.9.3"
val circeGeneric = "io.circe" %% "circe-generic" % "0.9.3"

unmanagedSourceDirectories in Compile ++= {
  val bloopBaseDir = root.getParentFile.getParentFile.getAbsoluteFile
  val integrationsMainDir = bloopBaseDir / "integrations"
  val pluginMainDir = integrationsMainDir / "sbt-bloop" / "src" / "main"
  List(
    root / "src" / "main" / "scala",
    bloopBaseDir / "config" / "src" / "main" / "scala",
    bloopBaseDir / "config" / "src" / "main" / s"scala-${Keys.scalaBinaryVersion.value}",
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
      typesafeConfig,
      metaconfigCore,
      metaconfigDocs,
      metaconfigConfig,
      circeDerivation
    )
  } else {
    List(
      typesafeConfig,
      circeCore,
      circeGeneric,
      compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
    )
  }
}
