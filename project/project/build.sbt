version in ThisBuild := "1.0.0-SNAPSHOT"
organization in ThisBuild := "ch.epfl.scala"

val sharedSettings = List(
  Keys.publishArtifact in (Compile, Keys.packageSrc) := false,
  Keys.publishArtifact in (Compile, Keys.packageDoc) := false
)

val emptySbtPlugin = project
  .in(file("target")./("empty-sbt-plugin"))
  .settings(sharedSettings)
  .settings(sbtPlugin := true)

val sbtBloopBuildJar = project
  .in(file("target")./("sbt-bloop-build"))
  .settings(sharedSettings)
  .settings(
    sbtPlugin := true,
    name := "sbt-bloop-build",
    scalacOptions in Compile :=
      (scalacOptions in Compile).value.filterNot(_ == "-deprecation"),
    libraryDependencies ++= List(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.4.0",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.4.0"
    ),
    // Let's add our sbt plugin sources to the module
    unmanagedSourceDirectories in Compile ++= {
      val baseDir = baseDirectory.value.getParentFile.getParentFile.getParentFile.getParentFile
      val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
      List(
        baseDir / "project" / "project",
        baseDir / "config" / ".jvm" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / "scala-2.11-13",
        pluginMainDir / "scala",
        pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
      )
    }
  )

// Create a proxy project instead of depending on plugin directly to work around https://github.com/sbt/sbt/issues/892
val sbtBloopBuildNakedJar = project
  .in(file("sbt-bloop-build-naked"))
  .settings(sharedSettings)
  .settings(
    name := "sbt-bloop-build-naked",
    libraryDependencies ++= List(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.4.0",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.4.0"
    ),
    products in Compile := {
      val packagedPluginJar = (packageBin in Compile in sbtBloopBuildJar).value.toPath

      // Proceed to remove META-INF, which contains sbt.autoplugins, from jar
      val classDirectory = Keys.classDirectory.in(Compile).value
      IO.unzip(packagedPluginJar.toFile, classDirectory)
      IO.delete(classDirectory / "META-INF")
      IO.delete(classDirectory / "sbt" / "sbt.autoplugins")

      List(classDirectory)
    }
  )

val root = project
  .in(file("."))
  .settings(sharedSettings)
  .dependsOn(sbtBloopBuildNakedJar)
  .settings(
    sbtPlugin := true,
    update := update
      .dependsOn(publishLocal in Compile in sbtBloopBuildJar)
      .dependsOn(publishLocal in Compile in sbtBloopBuildNakedJar)
      .value
  )
