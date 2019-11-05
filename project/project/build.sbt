version in ThisBuild := "1.0.0-SNAPSHOT"
scalaVersion in ThisBuild := "2.12.10"
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
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.0.0" % Provided,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.0.0" % Provided,
      "org.zeroturnaround" % "zt-exec" % "1.11" % Provided,
      "org.slf4j" % "slf4j-nop" % "1.7.2" % Provided,
      "me.vican.jorge" %% "snailgun-cli" % "0.3.1" % Provided,
      "io.get-coursier" %% "coursier" % "2.0.0-RC3-4" % Provided,
      "io.get-coursier" %% "coursier-cache" % "2.0.0-RC3-4" % Provided,
      "ch.epfl.scala" % "bsp4j" % "2.0.0-M4+10-61e61e87" % Provided,
      "net.java.dev.jna" % "jna" % "4.5.0",
      "net.java.dev.jna" % "jna-platform" % "4.5.0",
      "com.google.code.gson" % "gson" % "2.7",
      "com.google.code.findbugs" % "jsr305" % "3.0.2"
    ),
    toShadeClasses := {
      build.Shading.toShadeClasses(
        shadeNamespaces.value,
        toShadeJars.value,
        streams.value.log,
        verbose = false
      )
    },
    toShadeJars := {
      import java.nio.file.{Files, FileSystems}
      val eclipseJarsUnsignedDir = (Keys.crossTarget.value / "eclipse-jars-unsigned").toPath
      Files.createDirectories(eclipseJarsUnsignedDir)

      val sbtCompileDependencies = (dependencyClasspath in Compile in emptySbtPlugin).value
      val currentCompileDependencies = (fullClasspath in Compile).value
      val currentRuntimeDependencies = (fullClasspath in Runtime).value

      val dependenciesToShade = currentCompileDependencies.filterNot { dep =>
        sbtCompileDependencies.contains(dep) ||
        currentRuntimeDependencies.contains(dep)
      }

      dependenciesToShade.map(_.data).map {
        path =>
          val ppath = path.toString

          // Copy over jar and remove signed entries
          if (!ppath.contains("eclipse")) path
          else {
            val targetJar = eclipseJarsUnsignedDir.resolve(path.getName)
            if (!Files.exists(targetJar)) Files.copy(path.toPath, targetJar)
            val properties = new java.util.HashMap[String, String]()
            properties.put("create", "false")
            val targetUri = java.net.URI.create(s"jar:file:${targetJar.toAbsolutePath.toString}")
            val fs = FileSystems.newFileSystem(targetUri, properties)
            try {
              val metaInfDir = fs.getPath("META-INF")
              val signatures = List(".SF", ".DSA", ".RSA")
              if (Files.exists(metaInfDir)) {
                Files
                  .list(metaInfDir)
                  .filter(f => signatures.exists(sig => f.getFileName.toString.endsWith(sig)))
                  .forEach(f => { Files.delete(f) })
              }
            } finally fs.close()
            targetJar.toFile
          }
      }

    },
    shadingNamespace := "shaded.build",
    shadeIgnoredNamespaces := Set("com.google.gson"),
    shadeNamespaces := Set(
      "com.github.plokhotnyuk.jsoniter_scala",
      "machinist",
      "snailgun",
      "org.zeroturnaround",
      "io.github.soc",
      "org.slf4j",
      "scopt",
      "macrocompat",
      "com.zaxxer.nuprocess",
      "coursier",
      "shapeless",
      "argonaut",
      "org.checkerframework",
      "com.google",
      "org.codehaus",
      "ch.epfl.scala.bsp4j",
      "org.eclipse"
    ),
    // Let's add our sbt plugin sources to the module
    unmanagedSourceDirectories in Compile ++= {
      val baseDir = baseDirectory.value.getParentFile.getParentFile.getParentFile.getParentFile
      val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
      List(
        baseDir / "project" / "project",
        baseDir / "config" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / "scala-2.11-13",
        baseDir / "sockets" / "src" / "main" / "java",
        baseDir / "bloop4j" / "src" / "main" / "java",
        baseDir / "bloop4j" / "src" / "main" / "scala",
        baseDir / "bloopgun" / "src" / "main" / "scala",
        baseDir / "launcher" / "src" / "main" / "scala",
        pluginMainDir / "scala",
        pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
      )
    },
    packageBin in Compile := {
      val namespace = shadingNamespace.?.value.getOrElse {
        throw new NoSuchElementException("shadingNamespace key not set")
      }

      import sbt.util.{FileFunction, FileInfo}

      val packagedBin = packageBin.in(Compile).value
      val namespaces = shadeNamespaces.value
      val ignoredNamespaces = shadeIgnoredNamespaces.value
      val classes = toShadeClasses.value
      val jars = toShadeJars.value

      val inputs = Keys.sources.in(Compile).value.toSet
      val cacheDirectory = Keys.target.value / "shaded-inputs-cached"
      /*
      val cacheShading = FileFunction.cached(cacheDirectory, FileInfo.hash) { srcs =>
        Set(
          build.Shading.createPackage(packagedBin, Nil, namespace, namespaces, classes, jars)
        )
      }

      cacheShading(inputs).head
       */
      build.Shading
        .createPackage(packagedBin, Nil, namespace, namespaces, ignoredNamespaces, classes, jars)
    }
  )

// Create a proxy project instead of depending on plugin directly to work around https://github.com/sbt/sbt/issues/892
val sbtBloopBuildShadedNakedJar = project
  .in(file("target")./("sbt-bloop-build-shaded-naked"))
  .settings(sharedSettings)
  .settings(
    name := "sbt-bloop-build-shaded-naked",
    libraryDependencies ++= List(
      "net.java.dev.jna" % "jna" % "4.5.0",
      "net.java.dev.jna" % "jna-platform" % "4.5.0",
      "com.google.code.gson" % "gson" % "2.7",
      "com.google.code.findbugs" % "jsr305" % "3.0.2"
    ),
    products in Compile := {
      val packagedPluginJar = (packageBin in Compile in sbtBloopBuildShadedJar).value.toPath

      // Proceed to remove META-INF, which contains sbt.autoplugins, from jar
      val classDirectory = Keys.classDirectory.in(Compile).value
      IO.unzip(packagedPluginJar.toFile, classDirectory)
      IO.delete(classDirectory / "META-INF")

      /*
      import java.nio.file.{Files, FileSystems}
      val repackagedClassesDir = (Keys.crossTarget.value / "repackaged").toPath
      Files.createDirectories(repackagedClassesDir)
      val repackagedPluginJar = repackagedClassesDir.resolve(packagedPluginJar.getFileName)
      Files.deleteIfExists(repackagedPluginJar)
      Files.copy(packagedPluginJar, repackagedPluginJar)

      val properties = new java.util.HashMap[String, String]()
      properties.put("create", "false")
      val targetUri =
        java.net.URI.create(s"jar:file:${repackagedPluginJar.toAbsolutePath.toString}")
      val fs = FileSystems.newFileSystem(targetUri, properties)

      try {
        val autopluginsFile = fs.getPath("sbt", "sbt.autoplugins")
        Files.delete(autopluginsFile)
      } finally fs.close()

      List(repackagedPluginJar.toFile)
      */
      List(classDirectory)
    }
  )

val root = project
  .in(file("."))
  .settings(sharedSettings)
  .settings(
    sbtPlugin := true,
    libraryDependencies += "ch.epfl.scala" %% "sbt-bloop-build-shaded-naked" % "1.0.0-SNAPSHOT",
    update := update
      .dependsOn(publishLocal in Compile in sbtBloopBuildShadedJar)
      .dependsOn(publishLocal in Compile in sbtBloopBuildShadedNakedJar)
      .value
  )
