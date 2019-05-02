val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"
val circeCore = "io.circe" %% "circe-core" % "0.9.3"
val circeGeneric = "io.circe" %% "circe-generic" % "0.9.3"

val bloopBaseDir = ???
unmanagedSourceDirectories in Compile ++= {
  val integrationsMainDir = bloopBaseDir / "integrations"
  val pluginMainDir = integrationsMainDir / "sbt-bloop" / "src" / "main"
  val scalaDependentDir = {
    val scalaVersion = Keys.scalaBinaryVersion.value
    if (scalaVersion == "2.10")
      bloopBaseDir / "config" / "src" / "main" / s"scala-${Keys.scalaBinaryVersion.value}"
    else bloopBaseDir / "config" / "src" / "main" / s"scala-2.11-12"
  }

  List(
    bloopBaseDir / "config" / "src" / "main" / "scala",
    scalaDependentDir,
    pluginMainDir / "scala",
    pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
  )
}

libraryDependencies := {
  val sbtVersion = (sbtBinaryVersion in pluginCrossBuild).value
  val scalaVersion = (scalaBinaryVersion in update).value
  if (sbtVersion.startsWith("1.")) {
    List(circeDerivation)
  } else {
    val macros = compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
    List(circeCore, circeGeneric, macros)
  }
}
