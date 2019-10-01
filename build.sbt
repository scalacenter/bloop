import _root_.bloop.integrations.sbt.BloopDefaults
import build.BuildImplementation.BuildDefaults

// Tell bloop to aggregate source deps (benchmark) config files in the same bloop config dir
bloopAggregateSourceDependencies in Global := true

bloopExportJarClassifiers in ThisBuild := Some(Set("sources"))

/***************************************************************************************************/
/*                      This is the build definition of the source deps                            */
/***************************************************************************************************/
val benchmarkBridge = project
  .in(file(".benchmark-bridge-compilation"))
  .aggregate(BenchmarkBridgeCompilation)
  .disablePlugins(ScriptedPlugin)
  .settings(
    releaseEarly := { () },
    skip in publish := true,
    bloopGenerate in Compile := None,
    bloopGenerate in Test := None
  )

lazy val bloopShared = (project in file("shared"))
  .settings(
    name := "bloop-shared",
    libraryDependencies ++= Seq(
      Dependencies.bsp4s,
      Dependencies.zinc,
      Dependencies.xxHashLibrary,
      Dependencies.configDirectories,
      Dependencies.sbtTestInterface,
      Dependencies.sbtTestAgent
    )
  )

/***************************************************************************************************/
/*                            This is the build definition of the wrapper                          */
/***************************************************************************************************/
import build.Dependencies
import build.Dependencies.{
  Scala210Version,
  Scala211Version,
  Scala212Version,
  Sbt013Version,
  Sbt1Version
}

lazy val backend = project
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings ++ testSuiteSettings)
  .dependsOn(bloopShared)
  .settings(
    name := "bloop-backend",
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := BloopBackendInfoKeys,
    buildInfoObject := "BloopScalaInfo",
    libraryDependencies ++= List(
      Dependencies.javaDebug,
      Dependencies.nailgun,
      Dependencies.scalazCore,
      Dependencies.scalazConcurrent,
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.libraryManagement,
      Dependencies.sourcecode,
      Dependencies.monix,
      Dependencies.directoryWatcher,
      Dependencies.zt,
      Dependencies.brave,
      Dependencies.zipkinSender,
      Dependencies.pprint,
      Dependencies.difflib,
      Dependencies.asm,
      Dependencies.asmUtil
    )
  )

val publishJsonModuleSettings = List(
  publishM2Configuration := publishM2Configuration.value.withOverwrite(true),
  publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true),
  // We compile in both so that the maven integration can be tested locally
  publishLocal := publishLocal.dependsOn(publishM2).value
)

lazy val jsonConfig210 = project
  .in(file("config"))
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(publishJsonModuleSettings)
  .settings(
    name := "bloop-config",
    target := (file("config") / "target" / "json-config-2.10").getAbsoluteFile,
    scalaVersion := Scala210Version,
    libraryDependencies ++= {
      List(
        Dependencies.circeParser,
        Dependencies.circeCore,
        Dependencies.circeGeneric,
        compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
        Dependencies.scalacheck % Test
      )
    }
  )

lazy val jsonConfig211 = project
  .in(file("config"))
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(publishJsonModuleSettings)
  .settings(
    name := "bloop-config",
    target := (file("config") / "target" / "json-config-2.11").getAbsoluteFile,
    scalaVersion := Scala211Version,
    unmanagedSourceDirectories in Compile +=
      Keys.baseDirectory.value./("src")./("main")./("scala-2.11-12"),
    libraryDependencies ++= {
      List(
        Dependencies.circeParser,
        Dependencies.circeDerivation,
        Dependencies.scalacheck % Test
      )
    }
  )

// Needs to be called `jsonConfig` because of naming conflict with sbt universe...
lazy val jsonConfig212 = project
  .in(file("config"))
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(publishJsonModuleSettings)
  .settings(
    name := "bloop-config",
    unmanagedSourceDirectories in Compile +=
      Keys.baseDirectory.value./("src")./("main")./("scala-2.11-12"),
    target := (file("config") / "target" / "json-config-2.12").getAbsoluteFile,
    scalaVersion := Keys.scalaVersion.in(backend).value,
    scalacOptions := {
      scalacOptions.value.filterNot(opt => opt == "-deprecation"),
    },
    libraryDependencies ++= {
      List(
        Dependencies.circeParser,
        Dependencies.circeDerivation,
        Dependencies.scalacheck % Test
      )
    }
  )

lazy val jsonConfig213 = project
  .in(file("config"))
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(publishJsonModuleSettings)
  .settings(
    name := "bloop-config",
    unmanagedSourceDirectories in Compile +=
      Keys.baseDirectory.value./("src")./("main")./("scala-2.11-12"),
    target := (file("config") / "target" / "json-config-2.13").getAbsoluteFile,
    scalaVersion := "2.13.1",
    scalacOptions := {
      scalacOptions.value
        .filterNot(opt => opt == "-deprecation" || opt == "-Yno-adapted-args"),
    },
    libraryDependencies ++= {
      List(
        Dependencies.newCirceParser,
        Dependencies.newCirceDerivation
      )
    }
  )

lazy val sockets: Project = project
  .settings(
    crossPaths := false,
    autoScalaLibrary := false,
    description := "IPC: Unix Domain Socket and Windows Named Pipes for Java",
    libraryDependencies ++= Seq(Dependencies.jna, Dependencies.jnaPlatform)
  )

import build.BuildImplementation.jvmOptions
// For the moment, the dependency is fixed
lazy val frontend: Project = project
  .dependsOn(
    sockets,
    bloopShared,
    backend,
    backend % "test->test",
    jsonConfig212,
    buildpressConfig % "it->compile"
  )
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin)
  .configs(IntegrationTest)
  .settings(assemblySettings, releaseSettings)
  .settings(
    testSettings,
    testSuiteSettings,
    Defaults.itSettings,
    BuildDefaults.frontendTestBuildSettings,
    // Can be removed when metals upgrades to 1.3.0
    inConfig(IntegrationTest)(BloopDefaults.configSettings),
    inConfig(Compile)(
      build.BuildKeys.lazyFullClasspath := {
        val ownProductDirectories = Keys.productDirectories.value
        val dependencyClasspath = build.BuildImplementation.lazyDependencyClasspath.value
        ownProductDirectories ++ dependencyClasspath
      }
    )
  )
  .settings(
    name := "bloop-frontend",
    bloopName := "bloop",
    mainClass in Compile in run := Some("bloop.Cli"),
    bloopMainClass in Compile in run := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := bloopInfoKeys(nativeBridge03, nativeBridge04, jsBridge06, jsBridge10),
    javaOptions in run ++= jvmOptions,
    javaOptions in Test ++= jvmOptions,
    javaOptions in IntegrationTest ++= jvmOptions,
    libraryDependencies += Dependencies.graphviz % Test,
    fork in run := true,
    fork in Test := true,
    fork in run in IntegrationTest := true,
    parallelExecution in test := false,
    libraryDependencies ++= List(
      Dependencies.scalazCore,
      Dependencies.monix,
      Dependencies.caseApp,
      Dependencies.nuprocess
    ),
    dependencyOverrides += Dependencies.shapeless
  )

lazy val bloopgun: Project = project
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(GraalVMNativeImagePlugin)
  .settings(testSuiteSettings)
  .settings(
    name := "bloopgun-core",
    fork in run := true,
    fork in Test := true,
    parallelExecution in Test := false,
    libraryDependencies ++= List(
      //Dependencies.configDirectories,
      Dependencies.snailgun,
      // Use zt-exec instead of nuprocess because it doesn't require JNA (good for graalvm)
      Dependencies.ztExec,
      Dependencies.slf4jNop,
      Dependencies.coursier,
      Dependencies.coursierCache
    ),
    mainClass in GraalVMNativeImage := Some("bloop.bloopgun.Bloopgun"),
    graalVMNativeImageOptions ++= {
      val reflectionFile = Keys.sourceDirectory.in(Compile).value./("graal")./("reflection.json")
      assert(reflectionFile.exists)
      List(
        "--no-server",
        "--enable-http",
        "--enable-https",
        "-H:EnableURLProtocols=http,https",
        "--enable-all-security-services",
        "--no-fallback",
        s"-H:ReflectionConfigurationFiles=$reflectionFile",
        //"--allow-incomplete-classpath",
        "-H:+ReportExceptionStackTraces"
        //"--initialize-at-build-time=scala.Function1"
      )
    }
  )

def shadeSettingsForModule(
    moduleId: String,
    module: Reference,
    dependencyModules: List[Reference]
) = List(
  packageBin in Compile := {
    Def.taskDyn {
      val data = Keys.settingsData.value
      val baseJar = Keys.packageBin.in(Compile).in(module).value
      val unshadedDepJarTasks = dependencyModules.flatMap { dep =>
        Keys.packageBin.in(Compile).in(dep).get(data).toList
      }.join
      Def.taskDyn {
        val unshadedDepJars = unshadedDepJarTasks.value
        shadingPackageBin(baseJar, unshadedDepJars)
      }
    }.value
  },
  toShadeJars := {
    // Redefine toShadeJars as it seems broken in sbt-shading
    Def.taskDyn {
      val data = Keys.settingsData.value
      val projectDepModuleNames = dependencyModules.flatMap { dep =>
        Keys.name.get(data).toList
      }
      Def.task {
        val toolsJarPath = org.scaladebugger.SbtJdiTools.JavaTools.getAbsolutePath.toString
        val projectDepNames = Keys.name.in(module).value :: projectDepModuleNames
        val depJars = dependencyClasspath.in(Compile).in(module).value.map(_.data)
        depJars.filter { path =>
          val ppath = path.toString
          !(
            projectDepNames.exists(n => ppath.contains(n)) ||
              ppath.contains("sockets") || // Our own sockets library
              ppath.contains("scala-library") ||
              ppath.contains("scala-reflect") ||
              ppath.contains("scala-xml") ||
              ppath.contains("jna-platform") ||
              ppath.contains("jna") ||
              ppath.contains("macro-compat") ||
              ppath.contains(toolsJarPath)
          ) && path.exists && !path.isDirectory
        }
      }
    }.value
  },
  toShadeClasses := {
    // Only shade dependencies, not bloop code or Scala deps
    toShadeClasses.value.filter { p =>
      !(p.startsWith("bloop.") || p.startsWith("scala."))
    }
  },
  // Lists *all* Scala dependencies transitively for the shading to work correctly
  shadeNamespaces := Set(
    // Bloopgun direct and transitive deps
    "snailgun",
    "org.zeroturnaround",
    "io.github.soc",
    "org.slf4j",
    "scopt",
    "macrocompat",
    "com.zaxxer.nuprocess",
    // Coursier direct and transitive deps
    "coursier",
    "shapeless",
    "argonaut"
  )
)

lazy val bloopgunShaded = project
  .in(file("bloopgun/target/shaded-module"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(SbtJdiTools)
  .enablePlugins(BloopShadingPlugin)
  .settings(shadedModuleSettings)
  .settings(shadeSettingsForModule("bloopgun-core", bloopgun, Nil))
  .settings(
    name := "bloopgun",
    fork in run := true,
    fork in Test := true,
    bloopGenerate in Compile := None,
    bloopGenerate in Test := None
  )

lazy val launcher: Project = project
  .disablePlugins(ScriptedPlugin)
  .dependsOn(sockets, bloopgun, frontend % "test->test")
  .settings(testSuiteSettings)
  .settings(
    name := "bloop-launcher-core",
    fork in Test := true,
    parallelExecution in Test := false,
    libraryDependencies ++= List(
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.nuprocess
    )
  )

lazy val launcherShaded = project
  .in(file("launcher/target/shaded-module"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(SbtJdiTools)
  .enablePlugins(BloopShadingPlugin)
  .settings(shadedModuleSettings)
  .settings(shadeSettingsForModule("bloop-launcher-core", launcher, List(bloopgun)))
  .settings(
    name := "bloop-launcher",
    fork in run := true,
    fork in Test := true,
    bloopGenerate in Compile := None,
    bloopGenerate in Test := None,
    projectDependencies := {
      // Redefine ivy configuration to force publishing deps to shade
      val dependencies = projectDependencies.value
      Def
        .sequential(
          publishLocal in sockets,
          publishLocal in bloopgun,
          publishLocal in launcher
        )
        .value
      dependencies
    }
  )

lazy val bloop4j = project
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig212)
  .settings(
    name := "bloop4j",
    fork in run := true,
    fork in Test := true,
    libraryDependencies ++= List(
      Dependencies.bsp4j
    )
  )

lazy val benchmarks = project
  .dependsOn(frontend % "compile->it", BenchmarkBridgeCompilation % "compile->compile")
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin, JmhPlugin)
  .settings(benchmarksSettings(frontend))
  .settings(
    skip in publish := true
  )

val integrations = file("integrations")

def shadeSbtSettingsForModule(moduleId: String, module: Reference, dependencyToShade: Reference) =
  List(
    packageBin in Compile := {
      Def.taskDyn {
        val baseJar = Keys.packageBin.in(Compile).in(module).value
        val unshadedJars = List(Keys.packageBin.in(Compile).in(dependencyToShade).value)
        Def.taskDyn {
          shadingPackageBin(baseJar, unshadedJars)
        }
      }.value
    },
    toShadeJars := {
      // Redefine toShadeJars as it seems broken in sbt-shading
      Def.taskDyn {
        Def.task {
          val toolsJarPath = org.scaladebugger.SbtJdiTools.JavaTools.getAbsolutePath.toString
          val projectDepNames = List(Keys.name.in(dependencyToShade).value)

          // We get dependency jars from dependencyToShade because if we get them from `module`
          // we get all of the jars in the sbt universe and we only want to share our deps!
          val depJars = dependencyClasspath.in(Compile).in(dependencyToShade).value.map(_.data)
          depJars.filter { path =>
            val ppath = path.toString
            !(
              projectDepNames.exists(n => ppath.contains(n)) ||
                ppath.contains("scala-library") ||
                ppath.contains("scala-reflect") ||
                ppath.contains("scala-xml") ||
                ppath.contains("macro-compat") ||
                ppath.contains("scalamacros") ||
                ppath.contains(toolsJarPath)
            ) && path.exists && !path.isDirectory
          }
        }
      }.value
    },
    shadeNamespaces := Set(
      "machinist",
      "shapeless",
      "cats",
      "jawn",
      "org.typelevel.jawn",
      "io.circe"
    ),
    toShadeClasses := {
      // Only shade dependencies, not bloop config code
      toShadeClasses.value.filter(!_.startsWith("bloop"))
    }
  )

def defineShadedSbtPlugin(
    projectName: String,
    sbtVersion: String,
    sbtBloop: Reference,
    jsonConfig: Reference
) = {
  sbt
    .Project(projectName, integrations / "sbt-bloop" / "target" / s"sbt-bloop-shaded-$sbtVersion")
    .enablePlugins(BloopShadingPlugin)
    .disablePlugins(ScriptedPlugin)
    .disablePlugins(SbtJdiTools)
    .settings(sbtPluginSettings("sbt-bloop", sbtVersion, jsonConfig))
    .settings(shadedModuleSettings)
    .settings(shadeSbtSettingsForModule("sbt-bloop-core", sbtBloop, jsonConfig))
    .settings(
      fork in run := true,
      fork in Test := true,
      bloopGenerate in Compile := None,
      bloopGenerate in Test := None,
      target := (file("integrations") / "sbt-bloop-shaded" / "target" / sbtVersion).getAbsoluteFile
    )
}

lazy val sbtBloop10: Project = project
  .dependsOn(jsonConfig212)
  .enablePlugins(ScriptedPlugin)
  .in(integrations / "sbt-bloop")
  .settings(BuildDefaults.scriptedSettings)
  .settings(sbtPluginSettings("sbt-bloop-core", Sbt1Version, jsonConfig212))

lazy val sbtBloop10Shaded: Project =
  defineShadedSbtPlugin("sbtBloop10Shaded", Sbt1Version, sbtBloop10, jsonConfig212)

lazy val sbtBloop013 = project
  .dependsOn(jsonConfig210)
  // Let's remove scripted for 0.13, we only test 1.0
  .disablePlugins(ScriptedPlugin)
  .in(integrations / "sbt-bloop")
  .settings(scalaVersion := Scala210Version)
  .settings(sbtPluginSettings("sbt-bloop-core", Sbt013Version, jsonConfig210))

lazy val sbtBloop013Shaded =
  defineShadedSbtPlugin("sbtBloop013Shaded", Sbt013Version, sbtBloop013, jsonConfig210)

lazy val mavenBloop = project
  .in(integrations / "maven-bloop")
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig210)
  .settings(name := "maven-bloop", scalaVersion := Scala210Version)
  .settings(BuildDefaults.mavenPluginBuildSettings)

lazy val gradleBloop211 = project
  .in(file("integrations") / "gradle-bloop")
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig211)
  .settings(name := "gradle-bloop")
  .settings(BuildDefaults.gradlePluginBuildSettings)
  .settings(BuildInfoPlugin.buildInfoScopedSettings(Test))
  .settings(scalaVersion := Keys.scalaVersion.in(jsonConfig211).value)
  .settings(
    target := (file("integrations") / "gradle-bloop" / "target" / "gradle-bloop-2.11").getAbsoluteFile
  )
  .settings(
    sourceDirectories in Test := Nil,
    publishLocal := publishLocal.dependsOn(publishLocal.in(jsonConfig211)).value,
    bloopGenerate in Test := None,
    test in Test := Def.task {
      Keys.streams.value.log.error("Run 'gradleBloopTests/test' instead to test the gradle plugin.")
    }
  )

lazy val gradleBloop212 = project
  .in(file("integrations") / "gradle-bloop")
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig212, frontend % "test->test")
  .settings(BuildDefaults.gradlePluginBuildSettings, testSettings)
  .settings(BuildInfoPlugin.buildInfoScopedSettings(Test))
  .settings(scalaVersion := Keys.scalaVersion.in(jsonConfig212).value)
  .settings(
    target := (file("integrations") / "gradle-bloop" / "target" / "gradle-bloop-2.12").getAbsoluteFile
  )
  .settings(
    publishLocal := publishLocal.dependsOn(publishLocal.in(jsonConfig212)).value
  )

lazy val millBloop = project
  .in(integrations / "mill-bloop")
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig212)
  .settings(name := "mill-bloop")
  .settings(BuildDefaults.millModuleBuildSettings)

lazy val buildpressConfig = (project in file("buildpress-config"))
  .settings(
    scalaVersion := Scala212Version,
    libraryDependencies ++= List(
      Dependencies.circeParser,
      Dependencies.circeCore,
      Dependencies.circeGeneric
    ),
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full
    )
  )

lazy val buildpress = project
  .dependsOn(launcher, bloopShared, buildpressConfig)
  .settings(buildpressSettings)
  .settings(
    scalaVersion := Scala212Version,
    libraryDependencies ++= List(
      Dependencies.caseApp,
      Dependencies.nuprocess
    )
  )

val docs = project
  .in(file("docs-gen"))
  .dependsOn(frontend)
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
  .settings(
    name := "bloop-docs",
    moduleName := "bloop-docs",
    skip in publish := true,
    scalaVersion := Scala212Version,
    mdoc := run.in(Compile).evaluated,
    mainClass.in(Compile) := Some("bloop.Docs"),
    resources.in(Compile) ++= {
      List(baseDirectory.in(ThisBuild).value / "docs")
    }
  )

lazy val jsBridge06 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scalajs-0.6")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-js-bridge-0.6",
    libraryDependencies ++= List(
      Dependencies.scalaJsTools06,
      Dependencies.scalaJsSbtTestAdapter06,
      Dependencies.scalaJsEnvs06
    )
  )

lazy val jsBridge10 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scalajs-1.0")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-js-bridge-1.0",
    libraryDependencies ++= List(
      Dependencies.scalaJsLinker10,
      Dependencies.scalaJsLogging10,
      Dependencies.scalaJsIO10,
      Dependencies.scalaJsEnvs10,
      Dependencies.scalaJsEnvNode10,
      Dependencies.scalaJsEnvJsdomNode10,
      Dependencies.scalaJsSbtTestAdapter10
    )
  )

lazy val nativeBridge03 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scala-native-0.3")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-native-bridge-0.3",
    libraryDependencies += Dependencies.scalaNativeTools03,
    javaOptions in Test ++= jvmOptions,
    fork in Test := true
  )

lazy val nativeBridge04 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scala-native-0.4")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-native-bridge-0.4",
    libraryDependencies += Dependencies.scalaNativeTools04,
    javaOptions in Test ++= jvmOptions,
    fork in Test := true
  )

/* This project has the only purpose of forcing the resolution of some artifacts that fail spuriously to be fetched.  */
lazy val twitterIntegrationProjects = project
  .disablePlugins(CoursierPlugin, BloopPlugin)
  .in(file("target") / "twitter-integration-projects")
  .settings(
    resolvers += MavenRepository("twitter-resolver", "https://maven.twttr.com"),
    libraryDependencies += "com.hadoop.gplcompression" % "hadoop-lzo" % "0.4.19",
    libraryDependencies += "com.twitter.common" % "util" % "0.0.118",
    libraryDependencies += "com.twitter.common" % "quantity" % "0.0.96",
    libraryDependencies += "com.twitter" % "scrooge-serializer_2.11" % "4.12.0"
  )

val allProjects = Seq(
  bloopShared,
  backend,
  benchmarks,
  frontend,
  jsonConfig210,
  jsonConfig211,
  jsonConfig212,
  jsonConfig213,
  sbtBloop013,
  sbtBloop10,
  mavenBloop,
  gradleBloop211,
  gradleBloop212,
  millBloop,
  nativeBridge03,
  nativeBridge04,
  jsBridge06,
  jsBridge10,
  launcher,
  sockets,
  bloopgun
)

val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .disablePlugins(ScriptedPlugin)
  .aggregate(allProjectReferences: _*)
  .settings(
    releaseEarly := { () },
    skip in publish := true,
    crossSbtVersions := Seq(Sbt1Version, Sbt013Version),
    commands += BuildDefaults.exportProjectsInTestResourcesCmd,
    buildIntegrationsBase := (Keys.baseDirectory in ThisBuild).value / "build-integrations",
    twitterDodo := buildIntegrationsBase.value./("build-twitter"),
    exportCommunityBuild := {
      build.BuildImplementation
        .exportCommunityBuild(
          buildpress,
          jsonConfig210,
          jsonConfig212,
          sbtBloop013,
          sbtBloop10
        )
        .value
    }
  )

/**************************************************************************************************/
/*                      This is the corner for all the command definitions                        */
/**************************************************************************************************/
val publishLocalCmd = Keys.publishLocal.key.label

// Runs the scripted tests to setup integration tests
// ! This is used by the benchmarks too !
addCommandAlias(
  "install",
  Seq(
    s"${bloopShared.id}/$publishLocalCmd",
    s"${jsonConfig210.id}/$publishLocalCmd",
    s"${jsonConfig211.id}/$publishLocalCmd",
    s"${jsonConfig212.id}/$publishLocalCmd",
    s"${jsonConfig213.id}/$publishLocalCmd",
    s"${sbtBloop013.id}/$publishLocalCmd",
    s"${sbtBloop10.id}/$publishLocalCmd",
    s"${sbtBloop013Shaded.id}/$publishLocalCmd",
    s"${sbtBloop10Shaded.id}/$publishLocalCmd",
    s"${mavenBloop.id}/$publishLocalCmd",
    s"${gradleBloop211.id}/$publishLocalCmd",
    s"${gradleBloop212.id}/$publishLocalCmd",
    s"${backend.id}/$publishLocalCmd",
    s"${frontend.id}/$publishLocalCmd",
    s"${nativeBridge03.id}/$publishLocalCmd",
    s"${nativeBridge04.id}/$publishLocalCmd",
    s"${millBloop.id}/$publishLocalCmd",
    s"${jsBridge06.id}/$publishLocalCmd",
    s"${jsBridge10.id}/$publishLocalCmd",
    s"${sockets.id}/$publishLocalCmd",
    s"${bloopgun.id}/$publishLocalCmd",
    s"${launcher.id}/$publishLocalCmd",
    s"${bloopgunShaded.id}/$publishLocalCmd",
    s"${launcherShaded.id}/$publishLocalCmd",
    s"${buildpressConfig.id}/$publishLocalCmd",
    s"${buildpress.id}/$publishLocalCmd",
    // Force build info generators in frontend-test
    s"${frontend.id}/test:compile",
    "createLocalHomebrewFormula",
    "createLocalScoopFormula",
    "createLocalArchPackage",
    "generateInstallationWitness"
  ).mkString(";", ";", "")
)

addCommandAlias(
  "publishSbtBloop",
  Seq(
    s"${jsonConfig210.id}/$publishLocalCmd",
    s"${jsonConfig212.id}/$publishLocalCmd",
    s"${sbtBloop013.id}/$publishLocalCmd",
    s"${sbtBloop10.id}/$publishLocalCmd"
  ).mkString(";", ";", "")
)

val releaseEarlyCmd = releaseEarly.key.label

val allBloopReleases = List(
  s"${bloopShared.id}/$releaseEarlyCmd",
  s"${backend.id}/$releaseEarlyCmd",
  s"${frontend.id}/$releaseEarlyCmd",
  s"${jsonConfig210.id}/$releaseEarlyCmd",
  s"${jsonConfig211.id}/$releaseEarlyCmd",
  s"${jsonConfig212.id}/$releaseEarlyCmd",
  s"${jsonConfig213.id}/$releaseEarlyCmd",
  s"${sbtBloop013.id}/$releaseEarlyCmd",
  s"${sbtBloop10.id}/$releaseEarlyCmd",
  s"${sbtBloop013Shaded.id}/$releaseEarlyCmd",
  s"${sbtBloop10Shaded.id}/$releaseEarlyCmd",
  s"${mavenBloop.id}/$releaseEarlyCmd",
  s"${gradleBloop211.id}/$releaseEarlyCmd",
  s"${gradleBloop212.id}/$releaseEarlyCmd",
  s"${millBloop.id}/$releaseEarlyCmd",
  s"${nativeBridge03.id}/$releaseEarlyCmd",
  s"${nativeBridge04.id}/$releaseEarlyCmd",
  s"${jsBridge06.id}/$releaseEarlyCmd",
  s"${jsBridge10.id}/$releaseEarlyCmd",
  s"${sockets.id}/$releaseEarlyCmd",
  s"${bloopgun.id}/$releaseEarlyCmd",
  s"${launcher.id}/$releaseEarlyCmd",
  s"${bloopgunShaded.id}/$releaseEarlyCmd",
  s"${launcherShaded.id}/$releaseEarlyCmd",
  s"${buildpressConfig.id}/$releaseEarlyCmd",
  s"${buildpress.id}/$releaseEarlyCmd"
)

val allReleaseActions = allBloopReleases ++ List("sonatypeReleaseAll")
addCommandAlias("releaseBloop", allReleaseActions.mkString(";", ";", ""))
