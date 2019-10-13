val typesafeConfig = "com.typesafe" % "config" % "1.3.2"
val metaconfigCore = "com.geirsson" %% "metaconfig-core" % "0.6.0"
val metaconfigConfig = "com.geirsson" %% "metaconfig-typesafe-config" % "0.6.0"
val metaconfigDocs = "com.geirsson" %% "metaconfig-docs" % "0.6.0"
val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"
val jsoniterCore = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "1.0.0"
val jsoniterMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "1.0.0"

// Let's add our sbt plugin to the sbt too ;)
unmanagedSourceDirectories in Compile ++= {
  val baseDir =
    baseDirectory.value.getParentFile.getParentFile.getParentFile.getParentFile.getParentFile.getParentFile
  val integrationsMainDir = baseDir / "integrations"
  if (!integrationsMainDir.exists()) Nil
  else {
    val pluginMainDir = integrationsMainDir / "sbt-bloop" / "src" / "main"
    List(
      baseDir / "config" / "src" / "main" / "scala",
      baseDir / "config" / "src" / "main" / "scala-2.11-12",
      pluginMainDir / "scala",
      pluginMainDir / "scala-bloop-build"
    )
  }
}

libraryDependencies ++= List(
  typesafeConfig,
  metaconfigCore,
  metaconfigDocs,
  metaconfigConfig,
  circeDerivation,
  jsoniterCore,
  jsoniterMacros
)
