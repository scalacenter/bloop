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
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "1.0.0" % Provided,
      "org.zeroturnaround" % "zt-exec" % "1.11",
      "org.slf4j" % "slf4j-nop" % "1.7.2",
      "me.vican.jorge" %% "snailgun-cli" % "0.3.1",
      "io.get-coursier" %% "coursier" % "1.1.0-M14-4",
      "io.get-coursier" %% "coursier-cache" % "1.1.0-M14-4",
      "net.java.dev.jna" % "jna" % "4.5.0",
      "net.java.dev.jna" % "jna-platform" % "4.5.0",
      "ch.epfl.scala" % "bsp4j" % "2.0.0-M4+10-61e61e87"
    )
  )

val publishShadedLocal = taskKey[Unit]("Indirection layer to shade and cache")
val sbtBloopBuildShaded = project
  .in(file("target")./("sbt-bloop-build-shaded"))
  .enablePlugins(BloopShadingPlugin)
  .settings(sharedSettings)
  .settings(
    // Published name will be sbt-bloop-shaded because of `shading:publishLocal`
    name := "sbt-bloop-build-shaded",
    sbtPlugin := true,
    libraryDependencies ++= (libraryDependencies in sbtBloopBuildShadedDeps).value,
    toShadeJars := {
      // Redefine toShadeJars as it seems broken in sbt-shading
      Def.taskDyn {
        Def.task {
          // Only shade transitive dependencies, not bloop deps
          val a = (fullClasspath in Compile in sbtBloopBuildShadedDeps).value.map(_.data).filter {
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
              ) && path.exists
          }
          println(s"a $a")
          a
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
        baseDir / "config" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / "scala-2.11-13",
        baseDir / "sockets" / "src" / "main" / "java",
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

      build.Shading.createPackage(
        packageBin.in(Compile).value,
        Nil,
        namespace,
        shadeNamespaces.value,
        toShadeClasses.value,
        toShadeJars.value
      )
    },
    publishShadedLocal := {
      Def.taskDyn {
        import sbt.util.{FileFunction, FileInfo}
        var changed: Boolean = false
        val cacheDirectory = Keys.target.value / "shaded-inputs-cached"
        val detectChange = FileFunction.cached(cacheDirectory, FileInfo.hash) { srcs =>
          changed = true
          srcs
        }
        val inputs = Keys.sources.in(Compile).value.toSet //++
        //Keys.dependencyClasspath.in(Compile).value.map(_.data).toSet
        detectChange(inputs)
        if (changed) publishLocal
        else Def.task(())
      }.value
    }
  )

val root = project
  .in(file("."))
  .settings(sharedSettings)
  .settings(
    compile in Compile := {
      (publishShadedLocal in sbtBloopBuildShaded).value
      sbt.internal.inc.Analysis.empty
    }
  )
