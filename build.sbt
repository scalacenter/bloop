import _root_.bloop.integrations.sbt.BloopDefaults
import build.BuildImplementation.BuildDefaults
import xerial.sbt.Sonatype.SonatypeKeys

Global / useGpg := false

ThisBuild / dynverSeparator := "-"

// Tell bloop to aggregate source deps (benchmark) config files in the same bloop config dir
Global / bloopAggregateSourceDependencies := true

ThisBuild / bloopExportJarClassifiers := Some(Set("sources"))

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
  .settings(scalafixSettings)
  .settings(
    releaseEarly := { () },
    (publish / skip) := true,
    (Compile / bloopGenerate) := None,
    (Test / bloopGenerate) := None
  )

lazy val bloopShared = (project in file("shared"))
  .settings(scalafixSettings)
  .settings(
    name := "bloop-shared",
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
  .settings(scalafixSettings)
  .settings(testSettings ++ testSuiteSettings)
  .dependsOn(bloopShared)
  .settings(
    name := "bloop-backend",
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := Seq[BuildInfoKey](
      Keys.scalaVersion,
      Keys.scalaOrganization
    ),
    buildInfoObject := "BloopScalaInfo",
    libraryDependencies ++= List(
      Dependencies.nailgun,
      Dependencies.scalazCore,
      Dependencies.scalazConcurrent,
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

import build.BuildImplementation.jvmOptions
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
  .settings(scalafixSettings)
  .settings(releaseSettings)
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
    ),
    Test / resources := {
      val main = (Test / resources).value
      val dir = (ThisBuild / baseDirectory).value
      val log = streams.value
      val additionalResources =
        BuildDefaults.exportProjectsInTestResources(dir, log.log, enableCache = true)
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
  .settings(
    name := "bloop-frontend",
    bloopName := "bloop",
    (Compile / run / mainClass) := Some("bloop.Cli"),
    (Compile / run / bloopMainClass) := Some("bloop.Cli"),
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
    dependencyOverrides += Dependencies.shapeless
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
  graalVMNativeImageCommand := {
    val oldPath = graalVMNativeImageCommand.value
    if (!scala.util.Properties.isWin) oldPath
    else "C:/Users/runneradmin/.jabba/jdk/graalvm-ce-java11@21.1.0/bin/native-image.cmd"
  },
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
      "--no-server",
      "--enable-http",
      "--enable-https",
      "-H:EnableURLProtocols=http,https",
      "--enable-all-security-services",
      "--no-fallback",
      s"-H:ReflectionConfigurationFiles=$reflectionFile",
      "--allow-incomplete-classpath",
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
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(GraalVMNativeImagePlugin)
  .settings(testSuiteSettings)
  .settings(bloopgunSettings)
  .settings(target := (file("bloopgun") / "target" / "bloopgun-2.12").getAbsoluteFile)

lazy val bloopgun213: Project = project
  .in(file("bloopgun"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(GraalVMNativeImagePlugin)
  .settings(testSuiteSettings)
  .settings(
    scalaVersion := Dependencies.Scala213Version,
    target := (file("bloopgun") / "target" / "bloopgun-2.13").getAbsoluteFile
  )
  .settings(bloopgunSettings)

def shadeSettingsForModule(moduleId: String, module: Reference) = List(
  (Compile / packageBin) := {
    Def.taskDyn {
      val baseJar = (module / Compile / Keys.packageBin).value
      val unshadedJarDependencies =
        (module / Compile / internalDependencyAsJars).value.map(_.data)
      shadingPackageBin(baseJar, unshadedJarDependencies)
    }.value
  },
  toShadeJars := {
    val dependencyJars = (module / Runtime / dependencyClasspath).value.map(_.data)
    dependencyJars.flatMap { path =>
      val ppath = path.toString
      val shouldShadeJar = !(
        ppath.contains("scala-compiler") ||
          ppath.contains("scala-library") ||
          ppath.contains("scala-reflect") ||
          ppath.contains("scala-xml") ||
          ppath.contains("macro-compat") ||
          ppath.contains("bcprov-jdk15on") ||
          ppath.contains("bcpkix-jdk15on") ||
          ppath.contains("jna") ||
          ppath.contains("jna-platform") ||
          isJdiJar(path)
      ) && path.exists && !path.isDirectory

      if (!shouldShadeJar) Nil
      else List(path)
    }
  },
  shadeIgnoredNamespaces := Set("scala"),
  // Lists *all* Scala dependencies transitively for the shading to work correctly
  shadeNamespaces := Set(
    // Bloopgun direct and transitive deps
    "snailgun",
    "org.zeroturnaround",
    "io.github.soc",
    "org.slf4j",
    "scopt",
    "macrocompat",
    "com.github.plokhotnyuk.jsoniter_scala",
    "coursierapi"
  )
)

lazy val bloopgunShadedSettings = Seq(
  name := "bloopgun",
  (run / fork) := true,
  (Test / fork) := true,
  (Compile / bloopGenerate) := None,
  (Test / bloopGenerate) := None,
  libraryDependencies ++= List(Dependencies.scalaCollectionCompat)
)

lazy val bloopgunShaded = project
  .in(file("bloopgun/target/shaded-module-2.12"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(SbtJdiTools)
  .enablePlugins(BloopShadingPlugin)
  .settings(shadedModuleSettings)
  .settings(shadeSettingsForModule("bloopgun-core", bloopgun))
  .settings(bloopgunShadedSettings)

lazy val bloopgunShaded213 = project
  .in(file("bloopgun/target/shaded-module-2.13"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(SbtJdiTools)
  .enablePlugins(BloopShadingPlugin)
  .settings(shadedModuleSettings)
  .settings(shadeSettingsForModule("bloopgun-core", bloopgun213))
  .settings(bloopgunShadedSettings)
  .settings(scalaVersion := Dependencies.Scala213Version)

lazy val launcherTest = project
  .in(file("launcher-test"))
  .disablePlugins(ScriptedPlugin)
  .dependsOn(launcher, frontend % "test->test")
  .settings(scalafixSettings)
  .settings(testSuiteSettings)
  .settings(
    name := "bloop-launcher-test",
    (Test / fork) := true,
    (Test / parallelExecution) := false,
    libraryDependencies ++= List(
      Dependencies.coursierInterface
    )
  )

lazy val launcher = project
  .in(file("launcher-core"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .dependsOn(sockets, bloopgun)
  .settings(testSuiteSettings)
  .settings(
    name := "bloop-launcher-core",
    target := (file("launcher-core") / "target" / "launcher-2.12").getAbsoluteFile
  )

lazy val launcher213 = project
  .in(file("launcher-core"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .dependsOn(sockets, bloopgun213)
  .settings(testSuiteSettings)
  .settings(
    name := "bloop-launcher-core",
    scalaVersion := Dependencies.Scala213Version,
    target := (file("launcher-core") / "target" / "launcher-2.13").getAbsoluteFile
  )

lazy val launcherShadedSettings = Seq(
  name := "bloop-launcher",
  (run / fork) := true,
  (Test / fork) := true,
  (Compile / bloopGenerate) := None,
  (Test / bloopGenerate) := None,
  libraryDependencies ++= List(
    "net.java.dev.jna" % "jna" % "4.5.0",
    "net.java.dev.jna" % "jna-platform" % "4.5.0",
    Dependencies.scalaCollectionCompat
  )
)

lazy val launcherShaded = project
  .in(file("launcher-core/target/shaded-module-2.12"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(SbtJdiTools)
  .enablePlugins(BloopShadingPlugin)
  .settings(shadedModuleSettings)
  .settings(shadeSettingsForModule("bloop-launcher-core", launcher))
  .settings(launcherShadedSettings)

lazy val launcherShaded213 = project
  .in(file("launcher-core/target/shaded-module-2.13"))
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(SbtJdiTools)
  .enablePlugins(BloopShadingPlugin)
  .settings(shadedModuleSettings)
  .settings(shadeSettingsForModule("bloop-launcher-core", launcher213))
  .settings(launcherShadedSettings)
  .settings(scalaVersion := Dependencies.Scala213Version)

lazy val bloop4j = project
  .disablePlugins(ScriptedPlugin)
  .settings(scalafixSettings)
  .settings(
    name := "bloop4j",
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
  .settings(scalafixSettings)
  .settings(benchmarksSettings(frontend))
  .settings(
    (publish / skip) := true
  )

val integrations = file("integrations")

def isJdiJar(file: File): Boolean = {
  import org.scaladebugger.SbtJdiTools
  if (!System.getProperty("java.specification.version").startsWith("1.")) false
  else file.getAbsolutePath.contains(SbtJdiTools.JavaTools.getAbsolutePath.toString)
}

lazy val integrationUtils211 = project
  .in(integrations / "utils")
  .settings(
    scalafixSettings,
    scalaVersion := Dependencies.Scala211Version,
    libraryDependencies += Dependencies.bloopConfig,
    target := (file(
      "integrations"
    ) / "utils" / "target" / "utils-2.11").getAbsoluteFile
  )

lazy val integrationUtils212 = project
  .in(integrations / "utils")
  .settings(
    scalafixSettings,
    scalaVersion := Dependencies.Scala212Version,
    libraryDependencies += Dependencies.bloopConfig,
    target := (file(
      "integrations"
    ) / "utils" / "target" / "utils-2.12").getAbsoluteFile
  )

lazy val integrationUtils213 = project
  .in(integrations / "utils")
  .settings(
    scalafixSettings,
    scalaVersion := Dependencies.Scala213Version,
    libraryDependencies += Dependencies.bloopConfig,
    target := (file(
      "integrations"
    ) / "utils" / "target" / "utils-2.13").getAbsoluteFile
  )

lazy val sbtBloop10: Project = project
  .enablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .in(integrations / "sbt-bloop")
  .settings(
    BuildDefaults.scriptedSettings,
    sbtPluginSettings("sbt-bloop", Sbt1Version),
    libraryDependencies += Dependencies.bloopConfig
  )

lazy val buildpressConfig = (project in file("buildpress-config"))
  .settings(scalafixSettings)
  .settings(
    scalaVersion := Scala212Version,
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
  .settings(buildpressSettings)
  .settings(
    scalaVersion := Scala212Version,
    libraryDependencies ++= List(
      Dependencies.caseApp
    )
  )

val docs = project
  .in(file("docs-gen"))
  .dependsOn(frontend)
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
  .settings(scalafixSettings)
  .settings(
    name := "bloop-docs",
    moduleName := "bloop-docs",
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
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .settings(testSettings)
  .settings(
    name := jsBridge06Name,
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
  .disablePlugins(ScriptedPlugin)
  .disablePlugins(ScalafixPlugin)
  .settings(testSettings)
  .settings(
    name := jsBridge1Name,
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
  .disablePlugins(ScalafixPlugin)
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := nativeBridge04Name,
    libraryDependencies += Dependencies.scalaNativeTools04,
    (Test / javaOptions) ++= jvmOptions,
    (Test / fork) := true
  )

/* This project has the only purpose of forcing the resolution of some artifacts that fail spuriously to be fetched.  */
lazy val twitterIntegrationProjects = project
  .disablePlugins(BloopShadedPlugin)
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
  frontend,
  benchmarks,
  sbtBloop10,
  nativeBridge04,
  jsBridge06,
  jsBridge1,
  launcher,
  launcher213,
  launcherTest,
  sockets,
  bloopgun,
  bloopgun213,
  integrationUtils211,
  integrationUtils212,
  integrationUtils213
)

val allProjectsToRelease = Seq[ProjectReference](
  bloopShared,
  backend,
  frontend,
  sbtBloop10,
  nativeBridge04,
  jsBridge06,
  jsBridge1,
  sockets,
  bloopgun,
  bloopgunShaded,
  bloopgun213,
  bloopgunShaded213,
  launcher,
  launcherShaded,
  launcher213,
  launcherShaded213,
  buildpressConfig,
  buildpress
)

val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .disablePlugins(ScriptedPlugin)
  .aggregate(allProjectReferences: _*)
  .settings(
    releaseEarly := { () },
    (publish / skip) := true,
    crossSbtVersions := Seq(Sbt1Version, Sbt013Version),
    buildIntegrationsBase := (ThisBuild / Keys.baseDirectory).value / "build-integrations",
    publishLocalAllModules := {
      BuildDefaults
        .publishLocalAllModules(allProjectsToRelease)
        .value
    },
    releaseEarlyAllModules := {
      BuildDefaults
        .releaseEarlyAllModules(allProjectsToRelease)
        .value
    },
    releaseSonatypeBundle := {
      Def.taskDyn {
        val bundleDir = SonatypeKeys.sonatypeBundleDirectory.value
        // Do nothing if sonatype bundle doesn't exist
        if (!bundleDir.exists) Def.task("")
        else SonatypeKeys.sonatypeBundleRelease
      }.value
    },
    exportCommunityBuild := {
      build.BuildImplementation
        .exportCommunityBuild(
          buildpress,
          sbtBloop10
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
    "publishLocalAllModules",
    // Don't generate graalvm image if running in Windows
    if (isWindows) "" else "bloopgun/graalvm-native-image:packageBin",
    s"${frontend.id}/test:compile",
    "createLocalHomebrewFormula",
    "createLocalScoopFormula",
    "createLocalArchPackage"
  ).filter(!_.isEmpty)
    .mkString(";", ";", "")
)

val allReleaseActions = List("releaseEarlyAllModules", "sonatypeBundleRelease")
addCommandAlias("releaseBloop", allReleaseActions.mkString(";", ";", ""))
