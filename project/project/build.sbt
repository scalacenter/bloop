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

val sbtBloopBuildShadedJar = project
  .in(file("target")./("sbt-bloop-build-shaded"))
  .enablePlugins(BloopShadingPlugin)
  .settings(sharedSettings)
  .settings(
    // Published name will be sbt-bloop-shaded because of `shading:publishLocal`
    sbtPlugin := true,
    name := "sbt-bloop-build-shaded",
    scalacOptions in Compile :=
      (scalacOptions in Compile).value.filterNot(_ == "-deprecation"),
    libraryDependencies ++= List(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.4.0",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.4.0"
    ),
    toShadeClasses := {
      build.Shading.toShadeClasses(
        shadeNamespaces.value,
        shadeIgnoredNamespaces.value ++ shadeOwnNamespaces.value,
        toShadeJars.value,
        streams.value.log,
        verbose = false
      )
    },
    toShadeJars := {
      val sbtCompileDependencies = (dependencyClasspath in Compile in emptySbtPlugin).value
      val currentCompileDependencies = (dependencyClasspath in Runtime).value

      val dependenciesToShade = currentCompileDependencies.filterNot { dep =>
        sbtCompileDependencies.contains(dep)
      }

      import java.nio.file.{Files, FileSystems}
      val eclipseJarsUnsignedDir = (Keys.crossTarget.value / "eclipse-jars-unsigned").toPath
      Files.createDirectories(eclipseJarsUnsignedDir)
      dependenciesToShade.map(_.data).flatMap { path =>
        val ppath = path.toString

        // Copy over jar and remove signed entries
        if (!path.exists || !path.isFile) Nil
        else if (ppath.contains("gson") || ppath.contains("jsr") || ppath.contains("jna")) Nil
        else if (!ppath.contains("eclipse")) List(path)
        else {
          val targetJar = eclipseJarsUnsignedDir.resolve(path.getName)
          build.Shading.deleteSignedJarMetadata(path.toPath, targetJar)
          List(targetJar.toFile)
        }
      }

    },
    shadingNamespace := "shaded.build",
    shadeIgnoredNamespaces := Set("scala"),
    shadeNamespaces := Set(
      "com.github.plokhotnyuk.jsoniter_scala"
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
    },
    packageBin in Compile := {
      val namespace = shadingNamespace.?.value.getOrElse {
        throw new NoSuchElementException("shadingNamespace key not set")
      }

      val packagedBin = packageBin.in(Compile).value
      val namespaces = shadeNamespaces.value
      val ignored = shadeIgnoredNamespaces.value
      val classes = toShadeClasses.value
      val jars = toShadeJars.value

      val inputs = Keys.sources.in(Compile).value.toSet
      val cacheDirectory = Keys.target.value / "shaded-inputs-cached"

      import sbt.util.{FileFunction, FileInfo}
      val cacheShading = FileFunction.cached(cacheDirectory, FileInfo.hash) { srcs =>
        Set(
          build.Shading
            .createPackage(packagedBin, Nil, namespace, namespaces, ignored, classes, jars)
        )
      }

      cacheShading(inputs).head
    }
  )

// Create a proxy project instead of depending on plugin directly to work around https://github.com/sbt/sbt/issues/892
val sbtBloopBuildShadedNakedJar = project
  .in(file("sbt-bloop-build-shaded-naked"))
  .settings(sharedSettings)
  .settings(
    name := "sbt-bloop-build-shaded-naked",
    products in Compile := {
      val packagedPluginJar = (packageBin in Compile in sbtBloopBuildShadedJar).value.toPath

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
  .dependsOn(sbtBloopBuildShadedNakedJar)
  .settings(
    sbtPlugin := true,
    update := update
      .dependsOn(publishLocal in Compile in sbtBloopBuildShadedJar)
      .dependsOn(publishLocal in Compile in sbtBloopBuildShadedNakedJar)
      .value
  )
