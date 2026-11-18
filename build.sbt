import build.BuildImplementation.BuildDefaults
import build.BuildImplementation.jvmOptions
import build.Dependencies
import build.Dependencies.{
  Scala211Version,
  Scala212Version,
  Scala213Version,
  Scala33Version,
  SbtVersion,
  Scala3Version
}

ThisBuild / dynverSeparator := "-"

// Add hook for scalafmt validation
Global / onLoad ~= { old =>
  import java.nio.file._
  // In a git worktree `.git` is a file pointing at the real git dir, so the hook
  // belongs to the main checkout only. Skip it rather than fail the build load.
  if (!scala.util.Properties.isWin && Files.isDirectory(Paths.get(".git"))) {
    val prePush = Paths.get(".git", "hooks", "pre-push")
    Files.createDirectories(prePush.getParent)
    Files.write(
      prePush,
      """#!/bin/sh
        |set -eux
        |bin/scalafmt --diff --diff-branch main
        |git diff --exit-code
        |""".stripMargin.getBytes()
    )
    prePush.toFile.setExecutable(true)
  }
  old
}

val scalafixSettings: Seq[Setting[_]] = Seq(
  scalacOptions ++= {
    if (scalaVersion.value.startsWith("2.12")) Seq("-Ywarn-unused", "-Xlint:unused")
    else if (scalaVersion.value.startsWith("2.13")) Seq("-Wunused")
    else Seq("-Wunused:all")
  },
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision
)

// Settings for every module that is part of, or loaded by, the Scala 3 server
val serverJsoniterSettings: Seq[Setting[_]] = Seq(
  libraryDependencies += Dependencies.jsoniterCore213,
  excludeDependencies += Dependencies.jsoniterCore3
)

/**
 * ************************************************************************************************
 */
/*                      This is the build definition of the source deps                            */
/**
 * ************************************************************************************************
 */
val benchmarkBridge = project
  .in(file(".benchmark-bridge-compilation"))
  .aggregate(BenchmarkBridgeCompilation)
  .settings(
    scalafixSettings,
    (publish / skip) := true
  )

lazy val bloopShared = project
  .in(file("shared"))
  .settings(
    name := "bloop-shared",
    scalafixSettings,
    serverJsoniterSettings,
    libraryDependencies ++= Seq(
      Dependencies.jsoniterMacros,
      Dependencies.bsp4s.excludeAll(
        ExclusionRule(
          organization = "com.github.plokhotnyuk.jsoniter-scala"
        ),
        ExclusionRule(
          organization = "me.vican.jorge"
        )
      ),
      Dependencies.jsonrpc4s,
      Dependencies.monix,
      Dependencies.scalaCollectionCompat,
      Dependencies.zinc,
      Dependencies.scalaParallelCollections,
      Dependencies.jna,
      Dependencies.log4j,
      Dependencies.xxHashLibrary,
      Dependencies.configDirectories,
      Dependencies.windowsJniUtils,
      Dependencies.coursierInterface,
      Dependencies.sbtTestInterface,
      Dependencies.sbtTestAgent
    )
  )

/**
 * ************************************************************************************************
 */
/*                            This is the build definition of the wrapper                          */
/**
 * ************************************************************************************************
 */
lazy val backend = project
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(bloopShared)
  .settings(
    name := "bloop-backend",
    serverJsoniterSettings,
    scalafixSettings,
    testSettings ++ testSuiteSettings,
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := Seq[BuildInfoKey](
      Keys.scalaVersion,
      Keys.scalaOrganization
    ),
    buildInfoObject := "BloopScalaInfo",
    libraryDependencies ++= List(
      Dependencies.nailgun,
      Dependencies.scalazCore,
      Dependencies.coursierInterface,
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
      Dependencies.asmUtil,
      Dependencies.scalaCompiler
    )
  )

// For the moment, the dependency is fixed
lazy val frontend: Project = project
  .dependsOn(
    bloopShared,
    backend,
    backend % "test->test",
    buildpressConfig % "it->compile"
  )
  .enablePlugins(BuildInfoPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "bloop-frontend",
    serverJsoniterSettings,
    bloopName := "bloop",
    (Compile / run / mainClass) := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := List[BuildInfoKey](
      Keys.organization,
      build.BuildKeys.bloopName,
      Keys.version,
      Keys.scalaVersion,
      "zincVersion" -> Dependencies.zincVersion,
      "bspVersion" -> Dependencies.bspVersion,
      "nativeBridge04" -> (nativeBridge04Name + "_" + Keys.scalaBinaryVersion.value),
      "nativeBridge05" -> (nativeBridge05Name + "_" + Keys.scalaBinaryVersion.value),
      "jsBridge06" -> (jsBridge06Name + "_" + Keys.scalaBinaryVersion.value),
      "jsBridge1" -> (jsBridge1Name + "_" + Keys.scalaBinaryVersion.value),
      "lastSupportedSemanticdb" -> build.SemanticDbSupport.last,
      "millVersion" -> Dependencies.millVersion,
      "jupiterInterfaceVersion" -> Dependencies.jupiterInterfaceVersion
    ),
    (run / javaOptions) ++= jvmOptions,
    // Hard links between classes directories are opt-in, so the specs that check them enable it
    (Test / javaOptions) ++= jvmOptions :+ "-Dbloop.classes.hardlinks=true",
    (IntegrationTest / javaOptions) ++= jvmOptions,
    (run / fork) := true,
    (Test / fork) := true,
    (IntegrationTest / run / fork) := true,
    (test / parallelExecution) := false,
    libraryDependencies ++= List(
      Dependencies.jsoniterMacros % Provided,
      Dependencies.scalazCore,
      Dependencies.monix,
      Dependencies.caseApp,
      Dependencies.scalaDebugAdapter,
      Dependencies.bloopConfig,
      Dependencies.logback,
      Dependencies.libdaemonjvm,
      Dependencies.jupiterInterface
    ),
    // needed for tests and to be automatically updated
    Test / libraryDependencies += Dependencies.semanticdb intransitive (),
    scalafixSettings,
    testSettings,
    testSuiteSettings,
    releaseSettings,
    Defaults.itSettings,
    BuildDefaults.frontendTestBuildSettings,
    inConfig(Compile)(
      build.BuildKeys.lazyFullClasspath := {
        val ownProductDirectories = Keys.productDirectories.value
        val dependencyClasspath = build.BuildImplementation.lazyDependencyClasspath.value
        ownProductDirectories ++ dependencyClasspath
      }
    ),
    Test / resources := {
      val main = (Test / resources).value
      val dir = (ThisBuild / baseDirectory).value
      val log = streams.value
      // Before we export all test resources we ensure the current version of the sbt-bloop
      // plugin is published
      (sbtBloop / Keys.publishLocal).value
      (jsBridge1 / Keys.publishLocal).value
      (nativeBridge05 / Keys.publishLocal).value
      (nativeBridge04 / Keys.publishLocal).value
      val additionalResources =
        BuildDefaults.exportProjectsInTestResources(dir, log.log, enableCache = true, version.value)
      main ++ additionalResources
    },
    (Test / unmanagedResources / includeFilter) := {
      new FileFilter {
        def accept(file: File): Boolean = {
          val abs = file.getAbsolutePath
          !(
            abs.contains("scala-2.12") ||
              abs.contains("classes-") ||
              abs.contains("target")
          )
        }
      }
    }
  )

// bloop-rifle and the CLI are consumed from both Scala 2.13 and Scala 3.
// The default (and the native image) is the 3.3 LTS.
lazy val crossPublishedLauncherSettings = Seq(
  scalaVersion := Scala33Version,
  crossScalaVersions := List(Scala213Version, Scala33Version)
)

lazy val cliSettings = Seq(
  name := "bloop-cli",
  (run / fork) := true,
  (Test / fork) := true,
  (Test / parallelExecution) := false,
  libraryDependencies ++= List(
    Dependencies.caseApp,
    Dependencies.oslib,
    Dependencies.coursier,
    Dependencies.coursierJvm,
    Dependencies.dependency,
    Dependencies.directoriesJni,
    Dependencies.jsoniterCore,
    Dependencies.svm % Provided,
    Dependencies.jsoniterMacros % Provided
  ),
  (GraalVMNativeImage / mainClass) := Some("bloop.cli.Bloop"),
  graalVMNativeImageOptions ++= {
    val reflectionName =
      if (scalaVersion.value.startsWith("3.")) "reflection.json" else "reflection-2.13.json"
    val reflectionFile = (Compile / Keys.sourceDirectory).value./("graal")./(reflectionName)
    assert(reflectionFile.exists, s"${reflectionFile.getAbsolutePath()} doesn't exist")
    val baseOptions = List(
      "--add-exports=org.graalvm.nativeimage.base/com.oracle.svm.util=ALL-UNNAMED",
      "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jni=ALL-UNNAMED",
      "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED",
      // Do not let the native launcher consume -D/-X/-XX: options at startup, so that
      // arguments after `--` reach the server (e.g. `bloop test foo -- -Dkey=value` for
      // the test framework). Bloop.main re-applies the launcher's own -D properties that
      // appear before the command (bloop.java-opts, bloop.server, ...); -X/-XX: runtime
      // options for the client binary itself are no longer honoured (a native image
      // cannot apply them once started).
      // On GraalVM for JDK 21+ this needs -H:+UnlockExperimentalVMOptions before it.
      "-H:-ParseRuntimeOptions",
      "--no-fallback",
      "--enable-url-protocols=http,https",
      "--enable-all-security-services",
      s"-H:ReflectionConfigurationFiles=$reflectionFile",
      "-H:+ReportExceptionStackTraces"
    ) ++ initializeAtBuildTime(scalaVersion.value)

    if (scala.util.Properties.isWin) {
      val staticLibs = (Compile / Keys.sourceDirectory).value./("graal")./("static-libs")
      baseOptions :+ s"-H:CLibraryPath=${staticLibs}"
    } else {
      baseOptions
    }
  }
)

// Scala 2.13 still needs the structural-call and FunctionN classes initialized while the
// image is built. Scala 3.3 replaced that runtime; Statics$VM and ArrayOps$ are the classes
// native-image otherwise initializes too late (or not at all) and then rejects.
def initializeAtBuildTime(scalaVersion: String): List[String] = {
  val classes =
    if (scalaVersion.startsWith("3."))
      List(
        "scala.runtime.Statics$VM",
        "scala.collection.ArrayOps$"
      )
    else
      List(
        "scala.Symbol",
        "scala.Function1",
        "scala.Function2",
        "scala.runtime.StructuralCallSite",
        "scala.runtime.EmptyMethodCache"
      )
  classes.map(cls => s"--initialize-at-build-time=$cls")
}

lazy val bloopRifle: Project = project
  .in(file("bloop-rifle"))
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "bloop-rifle",
    crossPublishedLauncherSettings,
    libraryDependencies ++= List(
      Dependencies.bsp4j,
      Dependencies.scalaCollectionCompat,
      Dependencies.libdaemonjvm,
      Dependencies.expecty % Test,
      Dependencies.munit % Test
    ),
    buildInfoPackage := "bloop.rifle.internal",
    buildInfoKeys := List[BuildInfoKey](
      Keys.version,
      // Scala version used by the server itself
      "scalaVersion" -> Dependencies.Scala33Version,
      "bspVersion" -> Dependencies.bspVersion
    )
  )

lazy val cli: Project = project
  .in(file("cli"))
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .enablePlugins(BuildInfoPlugin, GraalVMNativeImagePlugin)
  .settings(
    testSuiteSettings,
    crossPublishedLauncherSettings,
    cliSettings,
    // Expose the server's classpath to integration tests so they can launch a
    // real bloop server through bloop-rifle (this module is cross-built for 2.13,
    // so it cannot depend on the frontend).
    Test / resourceGenerators += Def.task {
      val file = (Test / resourceManaged).value / "bloop-server-classpath.txt"
      val classpath = (frontend / Runtime / fullClasspath).value.map(_.data.getAbsolutePath)
      IO.write(file, classpath.mkString("\n"))
      Seq(file)
    }.taskValue
  )
  .dependsOn(bloopRifle)

lazy val benchmarks = project
  .dependsOn(frontend % "compile->it")
  .enablePlugins(BuildInfoPlugin, JmhPlugin)
  .settings(
    scalafixSettings,
    serverJsoniterSettings,
    benchmarksSettings(frontend),
    (publish / skip) := true
  )

val integrations = file("integrations")

lazy val sbtBloop: Project = project
  .disablePlugins(ScalafixPlugin)
  .enablePlugins(BuildInfoPlugin, SbtPlugin)
  .in(integrations / "sbt-bloop")
  .settings(
    scriptedBufferLog := false,
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq(
          "-Xmx1024M",
          "-Dplugin.version=" + version.value,
          "-DscalaVersion=" + scalaVersion.value
        )
    },
    name := "sbt-bloop",
    pluginCrossBuild / sbtVersion := (scalaBinaryVersion.value match {
      case "2.12" => SbtVersion
      case _ => "2.0.0"
    }),
    crossScalaVersions := List(Scala212Version, Scala3Version),
    scalaVersion := Dependencies.Scala212Version,
    libraryDependencies += Dependencies.bloopConfig,
    buildInfoPackage := "bloop.integrations.sbt",
    buildInfoKeys := List[BuildInfoKey](
      "latestScalaJsVersion" -> Dependencies.scalaJs1Version
    )
  )

lazy val buildpressConfig = (project in file("buildpress-config"))
  .settings(
    scalafixSettings,
    serverJsoniterSettings,
    libraryDependencies += Dependencies.jsoniterMacros % Provided
  )

lazy val buildpress = project
  .dependsOn(bloopShared, buildpressConfig)
  .settings(
    scalafixSettings,
    serverJsoniterSettings,
    (run / fork) := true,
    libraryDependencies ++= List(
      Dependencies.caseApp,
      Dependencies.ztExec
    )
  )

val docs = project
  .in(file("docs-gen"))
  .dependsOn(frontend)
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
  .settings(
    name := "bloop-docs",
    serverJsoniterSettings,
    moduleName := "bloop-docs",
    scalafixSettings,
    (publish / skip) := true,
    mdoc := (Compile / run).evaluated,
    (Compile / mainClass) := Some("bloop.Docs"),
    (Compile / resources) ++= {
      List((ThisBuild / baseDirectory).value / "docs")
    }
  )

val jsBridge06Name = "bloop-js-bridge-0-6"
lazy val jsBridge06 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scalajs-0.6")
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .settings(
    name := jsBridge06Name,
    serverJsoniterSettings,
    testSettings,
    libraryDependencies ++= List(
      Dependencies.scalaJsTools06,
      Dependencies.scalaJsSbtTestAdapter06,
      Dependencies.scalaJsEnvs06
    )
  )

val jsBridge1Name = "bloop-js-bridge-1"
lazy val jsBridge1 = project
  .dependsOn(bloopShared % Provided)
  .in(file("bridges") / "scalajs-1")
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .settings(
    name := jsBridge1Name,
    serverJsoniterSettings,
    testSettings,
    libraryDependencies ++= List(
      Dependencies.scalaJsLinker1,
      Dependencies.scalaJsLogging1,
      Dependencies.bloopConfig,
      Dependencies.scalaJsEnvs1,
      Dependencies.scalaJsEnvNode1,
      Dependencies.scalaJsEnvJsdomNode1,
      Dependencies.scalaJsSbtTestAdapter1
    )
  )

val nativeBridge04Name = "bloop-native-bridge-0-4"
lazy val nativeBridge04 = project
  .dependsOn(bloopShared % Provided)
  .in(file("bridges") / "scala-native-0.4")
  .disablePlugins(ScalafixPlugin, ScriptedPlugin)
  .settings(
    name := nativeBridge04Name,
    serverJsoniterSettings,
    testSettings,
    libraryDependencies ++= List(Dependencies.scalaNativeTools04, Dependencies.bloopConfig),
    (Test / javaOptions) ++= jvmOptions,
    (Test / fork) := true
  )

val nativeBridge05Name = "bloop-native-bridge-0-5"
lazy val nativeBridge05 = project
  .dependsOn(bloopShared % Provided)
  .in(file("bridges") / "scala-native-0.5")
  .disablePlugins(ScalafixPlugin, ScriptedPlugin)
  .settings(
    name := nativeBridge05Name,
    serverJsoniterSettings,
    testSettings,
    libraryDependencies ++= List(Dependencies.scalaNativeTools05, Dependencies.bloopConfig),
    (Test / javaOptions) ++= jvmOptions,
    (Test / fork) := true
  )

val allProjects = Seq(
  backend,
  benchmarks,
  bloopRifle,
  cli,
  bloopShared,
  buildpress,
  buildpressConfig,
  frontend,
  jsBridge06,
  jsBridge1,
  nativeBridge04,
  nativeBridge05,
  sbtBloop
)

val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .aggregate(allProjectReferences: _*)
  .settings(
    (publish / skip) := true,
    exportCommunityBuild := {
      build.BuildImplementation
        .exportCommunityBuild(
          buildpress,
          sbtBloop
        )
        .value
    }
  )

// Runs the scripted tests to setup integration tests
// ! This is used by the benchmarks too !
val isWindows = scala.util.Properties.isWin
addCommandAlias(
  "install",
  Seq(
    "publishLocal",
    "cli/graalvm-native-image:packageBin",
    s"${frontend.id}/test:compile",
    "createLocalHomebrewFormula",
    "createLocalScoopFormula",
    "createLocalArchPackage"
  ).filter(!_.isEmpty)
    .mkString(";", ";", "")
)
