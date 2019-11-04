version in ThisBuild := "1.0.0-SNAPSHOT"
scalaVersion in ThisBuild := "2.12.8"
organization in ThisBuild := "ch.epfl.scala"

val sharedSettings = List(
  Keys.publishArtifact in (Compile, Keys.packageSrc) := false,
  Keys.publishArtifact in (Compile, Keys.packageDoc) := false
)

val sbtBloopBuildShadedDeps = project
  .in(file("target")./("sbt-bloop-build-shaded-deps"))
  .settings(
    scalacOptions in Compile :=
      (scalacOptions in Compile).value.filterNot(_ == "-deprecation"),
    libraryDependencies ++= List(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "1.0.0",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "1.0.0",
      "org.zeroturnaround" % "zt-exec" % "1.11",
      "org.slf4j" % "slf4j-nop" % "1.7.2",
      "me.vican.jorge" %% "snailgun-cli" % "0.3.1",
      "io.get-coursier" %% "coursier" % "2.0.0-RC3-4",
      "io.get-coursier" %% "coursier-cache" % "2.0.0-RC3-4",
      "net.java.dev.jna" % "jna" % "4.5.0",
      "net.java.dev.jna" % "jna-platform" % "4.5.0",
      "ch.epfl.scala" % "bsp4j" % "2.0.0-M4+10-61e61e87"
    )
  )

val sbtBloopBuildShadedJar = project
  .in(file("target")./("sbt-bloop-build-shaded"))
  .enablePlugins(BloopShadingPlugin)
  .settings(sharedSettings)
  .settings(
    // Published name will be sbt-bloop-shaded because of `shading:publishLocal`
    sbtPlugin := true,
    name := "sbt-bloop-build-shaded-raw",
    libraryDependencies ++= (libraryDependencies in sbtBloopBuildShadedDeps).value,
    toShadeClasses := {
      build.Shading.toShadeClasses(
        shadeNamespaces.value,
        toShadeJars.value,
        streams.value.log,
        verbose = false
      )
    },
    toShadeJars := {
      // Redefine toShadeJars as it seems broken in sbt-shading
      Def.taskDyn {
        Def.task {
          // Only shade transitive dependencies, not bloop deps
          (fullClasspath in Compile in sbtBloopBuildShadedDeps).value.map(_.data).filter {
            path =>
              val ppath = path.toString
              !(
                ppath.contains("scala-compiler") ||
                  ppath.contains("scala-library") ||
                  ppath.contains("scala-reflect") ||
                  ppath.contains("scala-xml") ||
                  ppath.contains("macro-compat") ||
                  ppath.contains("jna-platform") ||
                  ppath.contains("jna") ||
                  ppath.contains("jsr305") ||
                  ppath.contains("gson") ||
                  ppath.contains("google") ||
                  // Eclipse jars are signed and cannot be uberjar'd
                  ppath.contains("eclipse") ||
                  ppath.contains("scalamacros")
              ) && path.isFile
          }
        }
      }.value
    },
    shadingNamespace := "shaded.build",
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
      //"com.google",
      "org.codehaus",
      "ch.epfl.scala.bsp4j"
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
      val classes = toShadeClasses.value
      val jars = toShadeJars.value

      val inputs = Keys.sources.in(Compile).value.toSet
      val cacheDirectory = Keys.target.value / "shaded-inputs-cached"
      val cacheShading = FileFunction.cached(cacheDirectory, FileInfo.hash) { srcs =>
        Set(
          build.Shading.createPackage(packagedBin, Nil, namespace, namespaces, classes, jars)
        )
      }

      cacheShading(inputs).head
    },
    exportJars := true,
    discoveredSbtPlugins in Compile := {
      sbt.internal.PluginDiscovery.emptyDiscoveredNames
    }
  )

val sbtBloopBuildShaded = project
  .in(file("target")./("sbt-bloop-build-shaded-complete"))
  .settings(sharedSettings)
  .settings(
    sbtPlugin := true,
    exportJars := true,
    name := "sbt-bloop-build-shaded",
    compileInputs in Compile in compile := {
      // Trigger packageBin so that next metaproject can have access to it
      val fatJar = (packageBin in Compile in sbtBloopBuildShadedJar).value

      val inputs = (compileInputs in Compile in compile).value
      val classDir = (classDirectory in Compile).value
      IO.unzip(fatJar, classDir)
      IO.delete(classDir / "META-INF" / "MANIFEST.MF")
      inputs
    },
    discoveredSbtPlugins in Compile := {
      val autoPlugins = List("bloop.integrations.sbt.BloopPlugin")
      new sbt.internal.PluginDiscovery.DiscoveredNames(autoPlugins, Nil)
    }
  )

/*
 * Most of the machinery here is done to work around https://github.com/sbt/sbt/issues/892
 */

val root = project
  .in(file("."))
  .settings(sharedSettings)
  .dependsOn(sbtBloopBuildShaded)
  .settings(
    sbtPlugin := true,
    exportJars := true,
    discoveredSbtPlugins in Compile := {
      // Trigger publish local of sbt-bloop shaded so that community build projects have access too
      (Keys.publishLocal in Compile in sbtBloopBuildShaded).value

      val autoPlugins = List("bloop.integrations.sbt.BloopPlugin")
      new sbt.internal.PluginDiscovery.DiscoveredNames(autoPlugins, Nil)
    }
  )
