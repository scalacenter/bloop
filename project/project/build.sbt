version in ThisBuild := "1.0.0-SNAPSHOT"
scalaVersion in ThisBuild := "2.12.8"
organization in ThisBuild := "ch.epfl.scala"

val sharedSettings = List(
  Keys.publishArtifact in (Shading, Keys.packageSrc) := false,
  Keys.publishArtifact in (Shading, Keys.packageDoc) := false
)

val sbtBloopBuildShadedDeps = project
  .in(file("target")./("sbt-bloop-build-shaded-deps"))
  .settings(
    libraryDependencies ++= List(
      // Don't shade scala-reflect as it creates misbehaviors
      "io.circe" %% "circe-parser" % "0.9.3",
      "io.circe" %% "circe-derivation" % "0.9.0-M3"
    )
  )

val publishShadedLocal = taskKey[Unit]("Indirection layer to shade and cache")
val sbtBloopBuildShaded = project
  .in(file("target")./("sbt-bloop-build-shaded"))
  .enablePlugins(ShadingPlugin)
  .settings(sharedSettings)
  .settings(
    // Published name will be sbt-bloop-shaded because of `shading:publishLocal`
    name := "sbt-bloop-build-shaded",
    sbtPlugin := true,
    libraryDependencies ++= (libraryDependencies in sbtBloopBuildShadedDeps).value,
    toShadeJars in Shading := {
      // Redefine toShadeJars as it seems broken in sbt-shading
      Def.taskDyn {
        Def.task {
          // Only shade transitive dependencies, not bloop deps
          (fullClasspath in Compile in sbtBloopBuildShadedDeps).value.map(_.data).filter { path =>
            val ppath = path.toString
            !(
              ppath.contains("scala-library") ||
                ppath.contains("scala-reflect") ||
                ppath.contains("scala-xml") ||
                ppath.contains("macro-compat") ||
                ppath.contains("scalamacros")
            ) && path.exists
          }
        }
      }.value
    },
    shadingNamespace := "build",
    shadeNamespaces := Set(
      "machinist",
      "shapeless",
      "cats",
      "jawn",
      "io.circe"
    ),
    // Let's add our sbt plugin sources to the module
    unmanagedSourceDirectories in Compile ++= {
      val baseDir = baseDirectory.value.getParentFile.getParentFile.getParentFile.getParentFile
      val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
      List(
        baseDir / "config" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / "scala-2.11-12",
        pluginMainDir / "scala",
        pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
      )
    },
    publishShadedLocal in Shading := {
      Def.taskDyn {
        import sbt.util.{FileFunction, FileInfo}
        var changed: Boolean = false
        val cacheDirectory = Keys.target.value / "sources-cached"
        val detectChange = FileFunction.cached(cacheDirectory, FileInfo.hash) { srcs =>
          changed = true
          srcs
        }
        detectChange(Keys.sources.in(Compile).value.toSet)
        if (changed) publishLocal in Shading
        else Def.task(())
      }.value
    }
  )

val root = project
  .in(file("."))
  .settings(sharedSettings)
  .settings(
    compile in Compile := {
      (publishShadedLocal in Shading in sbtBloopBuildShaded).value
      sbt.internal.inc.Analysis.empty
    }
  )
