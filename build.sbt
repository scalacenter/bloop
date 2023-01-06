import build.BuildImplementation.BuildDefaults
import build.BuildImplementation.jvmOptions
import build.Dependencies
import build.Dependencies.{Scala211Version, Scala212Version, SbtVersion}

ThisBuild / dynverSeparator := "-"

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"

// Add hook for scalafmt validation
Global / onLoad ~= { old =>
  if (!scala.util.Properties.isWin) {
    import java.nio.file._
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
    if (scalaVersion.value.startsWith("2.11")) Seq("-Ywarn-unused-import")
    else if (scalaVersion.value.startsWith("2.12")) Seq("-Ywarn-unused", "-Xlint:unused")
    else if (scalaVersion.value.startsWith("2.13")) Seq("-Wunused")
    else Seq.empty
  },
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision
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
  .disablePlugins(ScriptedPlugin)
  .settings(
    scalafixSettings,
    (publish / skip) := true
  )

lazy val bloopShared = project
  .in(file("shared"))
  .settings(
    name := "bloop-shared",
    scalafixSettings,
    libraryDependencies ++= Seq(
      Dependencies.jsoniterCore,
      Dependencies.jsoniterMacros,
      Dependencies.bsp4s excludeAll ExclusionRule(
        organization = "com.github.plokhotnyuk.jsoniter-scala"
      ),
      Dependencies.zinc,
      Dependencies.log4j,
      Dependencies.xxHashLibrary,
      Dependencies.configDirectories,
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
  .disablePlugins(ScriptedPlugin)
  .dependsOn(bloopShared)
  .settings(
    name := "bloop-backend",
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
      Dependencies.asmUtil
    )
  )

lazy val sockets: Project = project
  .settings(
    crossPaths := false,
    autoScalaLibrary := false,
    description := "IPC: Unix Domain Socket and Windows Named Pipes for Java",
    libraryDependencies ++= Seq(Dependencies.jna, Dependencies.jnaPlatform),
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
    (Compile / doc / sources) := Nil
  )

// For the moment, the dependency is fixed
lazy val frontend: Project = project
  .dependsOn(
    sockets,
    bloopShared,
    backend,
    backend % "test->test",
    buildpressConfig % "it->compile"
  )
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "bloop-frontend",
    bloopName := "bloop",
    (Compile / run / mainClass) := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := List[BuildInfoKey](
      Keys.organization,
      build.BuildKeys.bloopName,
      Keys.version,
      Keys.scalaVersion,
      nailgunClientLocation,
      "zincVersion" -> Dependencies.zincVersion,
      "bspVersion" -> Dependencies.bspVersion,
      "nativeBridge04" -> (nativeBridge04Name + "_" + Keys.scalaBinaryVersion.value),
      "jsBridge06" -> (jsBridge06Name + "_" + Keys.scalaBinaryVersion.value),
      "jsBridge1" -> (jsBridge1Name + "_" + Keys.scalaBinaryVersion.value)
    ),
    (run / javaOptions) ++= jvmOptions,
    (Test / javaOptions) ++= jvmOptions,
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
      Dependencies.bloopConfig
    ),
    dependencyOverrides += Dependencies.shapeless,
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

lazy val bloopgunSettings = Seq(
  name := "bloopgun-core",
  (run / fork) := true,
  (Test / fork) := true,
  (Test / parallelExecution) := false,
  buildInfoPackage := "bloopgun.internal.build",
  buildInfoKeys := List(Keys.version),
  buildInfoObject := "BloopgunInfo",
  libraryDependencies ++= List(
    // Dependencies.configDirectories,
    Dependencies.snailgun,
    // Use zt-exec instead of nuprocess because it doesn't require JNA (good for graalvm)
    Dependencies.ztExec,
    Dependencies.slf4jNop,
    Dependencies.coursierInterface,
    Dependencies.coursierInterfaceSubs,
    Dependencies.jsoniterCore,
    Dependencies.jsoniterMacros % Provided
  ),
  (GraalVMNativeImage / mainClass) := Some("bloop.bloopgun.Bloopgun"),
  graalVMNativeImageOptions ++= {
    val reflectionFile = (Compile / Keys.sourceDirectory).value./("graal")./("reflection.json")
    val securityOverridesFile =
      (Compile / Keys.sourceDirectory).value./("graal")./("java.security.overrides")
    assert(reflectionFile.exists, s"${reflectionFile.getAbsolutePath()} doesn't exist")
    assert(
      securityOverridesFile.exists,
      s"${securityOverridesFile.getAbsolutePath()} doesn't exist"
    )
    List(
      "--enable-http",
      "--enable-https",
      "--enable-url-protocols=http,https",
      "--enable-all-security-services",
      "--no-fallback",
      s"-H:ReflectionConfigurationFiles=$reflectionFile",
      "-H:+ReportExceptionStackTraces",
      s"-J-Djava.security.properties=$securityOverridesFile",
      s"-Djava.security.properties=$securityOverridesFile",
      "--initialize-at-build-time=scala.Symbol",
      "--initialize-at-build-time=scala.Function1",
      "--initialize-at-build-time=scala.Function2",
      "--initialize-at-build-time=scala.runtime.StructuralCallSite",
      "--initialize-at-build-time=scala.runtime.EmptyMethodCache"
    )
  }
)

lazy val bloopgun: Project = project
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .enablePlugins(BuildInfoPlugin, GraalVMNativeImagePlugin)
  .settings(
    testSuiteSettings,
    bloopgunSettings,
    target := (file("bloopgun") / "target" / "bloopgun-2.12").getAbsoluteFile
  )

lazy val bloopgun213: Project = project
  .in(file("bloopgun"))
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .enablePlugins(BuildInfoPlugin, GraalVMNativeImagePlugin)
  .settings(
    testSuiteSettings,
    bloopgunSettings,
    scalaVersion := Dependencies.Scala213Version,
    target := (file("bloopgun") / "target" / "bloopgun-2.13").getAbsoluteFile
  )

lazy val launcherTest = project
  .in(file("launcher-test"))
  .disablePlugins(ScriptedPlugin)
  .dependsOn(launcher, frontend % "test->test")
  .settings(
    name := "bloop-launcher-test",
    (publish / skip) := true,
    scalafixSettings,
    testSuiteSettings,
    (Test / fork) := true,
    (Test / parallelExecution) := false,
    libraryDependencies ++= List(
      Dependencies.coursierInterface
    )
  )

lazy val launcher = project
  .in(file("launcher-core"))
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .dependsOn(sockets, bloopgun)
  .settings(
    name := "bloop-launcher-core",
    testSuiteSettings,
    target := (file("launcher-core") / "target" / "launcher-2.12").getAbsoluteFile
  )

lazy val launcher213 = project
  .in(file("launcher-core"))
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .dependsOn(sockets, bloopgun213)
  .settings(
    name := "bloop-launcher-core",
    testSuiteSettings,
    scalaVersion := Dependencies.Scala213Version,
    target := (file("launcher-core") / "target" / "launcher-2.13").getAbsoluteFile
  )

lazy val bloop4j = project
  .disablePlugins(ScriptedPlugin)
  .settings(
    name := "bloop4j",
    scalafixSettings,
    (run / fork) := true,
    (Test / fork) := true,
    libraryDependencies ++= List(
      Dependencies.bsp4j,
      Dependencies.bloopConfig
    )
  )

lazy val benchmarks = project
  .dependsOn(frontend % "compile->it", BenchmarkBridgeCompilation % "compile->compile")
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin, JmhPlugin)
  .settings(
    scalafixSettings,
    benchmarksSettings(frontend),
    (publish / skip) := true
  )

val integrations = file("integrations")

lazy val sbtBloop: Project = project
  .enablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .in(integrations / "sbt-bloop")
  .settings(
    scriptedBufferLog := false,
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    name := "sbt-bloop",
    sbtPlugin := true,
    sbtVersion := SbtVersion,
    target := (file("integrations") / "sbt-bloop" / "target" / SbtVersion).getAbsoluteFile,
    libraryDependencies += Dependencies.bloopConfig
  )

lazy val buildpressConfig = (project in file("buildpress-config"))
  .settings(
    scalaVersion := Scala212Version,
    scalafixSettings,
    libraryDependencies ++= List(
      Dependencies.jsoniterCore,
      Dependencies.jsoniterMacros % Provided
    ),
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full
    )
  )

lazy val buildpress = project
  .dependsOn(bloopgun, bloopShared, buildpressConfig)
  .settings(
    scalaVersion := Scala212Version,
    (run / fork) := true,
    libraryDependencies ++= List(
      Dependencies.caseApp
    )
  )

val docs = project
  .in(file("docs-gen"))
  .dependsOn(frontend)
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
  .settings(
    name := "bloop-docs",
    moduleName := "bloop-docs",
    scalafixSettings,
    (publish / skip) := true,
    scalaVersion := Scala212Version,
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
    testSettings,
    libraryDependencies ++= List(
      Dependencies.scalaJsTools06,
      Dependencies.scalaJsSbtTestAdapter06,
      Dependencies.scalaJsEnvs06
    )
  )

val jsBridge1Name = "bloop-js-bridge-1"
lazy val jsBridge1 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scalajs-1")
  .disablePlugins(ScriptedPlugin, ScalafixPlugin)
  .settings(
    name := jsBridge1Name,
    testSettings,
    libraryDependencies ++= List(
      Dependencies.scalaJsLinker1,
      Dependencies.scalaJsLogging1,
      Dependencies.scalaJsEnvs1,
      Dependencies.scalaJsEnvNode1,
      Dependencies.scalaJsEnvJsdomNode1,
      Dependencies.scalaJsSbtTestAdapter1
    )
  )

val nativeBridge04Name = "bloop-native-bridge-0-4"
lazy val nativeBridge04 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scala-native-0.4")
  .disablePlugins(ScalafixPlugin, ScriptedPlugin)
  .settings(
    name := nativeBridge04Name,
    testSettings,
    libraryDependencies += Dependencies.scalaNativeTools04,
    (Test / javaOptions) ++= jvmOptions,
    (Test / fork) := true
  )

val allProjects = Seq(
  backend,
  benchmarks,
  bloopgun,
  bloopgun213,
  bloopShared,
  buildpress,
  buildpressConfig,
  frontend,
  jsBridge06,
  jsBridge1,
  launcher,
  launcher213,
  launcherTest,
  nativeBridge04,
  sbtBloop,
  sockets
)

val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .disablePlugins(ScriptedPlugin)
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
    "bloopgun/graalvm-native-image:packageBin",
    s"${frontend.id}/test:compile",
    "createLocalHomebrewFormula",
    "createLocalScoopFormula",
    "createLocalArchPackage"
  ).filter(!_.isEmpty)
    .mkString(";", ";", "")
)
