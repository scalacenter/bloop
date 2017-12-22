/***************************************************************************************************/
/*                      This is the build definition of the zinc integration                       */
/***************************************************************************************************/
// Remember, `scripted` and `cachedPublishLocal` are defined here via aggregation
val bridgeIntegration = project
  .in(file(".bridge"))
  .aggregate(ZincBridge)
  .settings(
    skip in publish := true,
    scalaVersion := (scalaVersion in ZincBridge).value,
    crossScalaVersions := (crossScalaVersions in ZincBridge).value,
  )

val zincIntegration = project
  .in(file(".zinc"))
  .aggregate(ZincRoot)
  .settings(
    skip in publish := true,
    scalaVersion := (scalaVersion in ZincRoot).value,
    // This only covers 2.12 and 2.11, but this is enough.
    crossScalaVersions := (crossScalaVersions in ZincRoot).value,
  )

// Work around a sbt-scalafmt but that forces us to define `scalafmtOnCompile` in sourcedeps
val SbtConfig = com.lucidchart.sbt.scalafmt.ScalafmtSbtPlugin.autoImport.Sbt
val hijackScalafmtOnCompile = SettingKey[Boolean]("scalafmtOnCompile", "Just having fun.")
val nailgun = project
  .in(file(".nailgun"))
  .aggregate(NailgunServer)
  .settings(
    skip in publish := true,
    hijackScalafmtOnCompile in SbtConfig in NailgunBuild := false,
  )

val benchmarkBridge = project
  .in(file(".benchmark-bridge-compilation"))
  .aggregate(BenchmarkBridgeCompilation)
  .settings(
    skip in publish := true
  )

/***************************************************************************************************/
/*                            This is the build definition of the wrapper                          */
/***************************************************************************************************/
import build.Dependencies

// This alias performs all the steps necessary to publish Bloop locally
addCommandAlias(
  "install",
  Seq(
    "+bridgeIntegration/publishLocal",
    "+zincIntegration/publishLocal",
    "^sbtBloop/publishLocal",
    "nailgun/publishLocal",
    "backend/publishLocal",
    s"frontend/publishLocal"
  ).mkString(";", ";", "")
)

val backend = project
  .dependsOn(Zinc, NailgunServer)
  .settings(testSettings)
  .settings(
    name := "bloop-backend",
    libraryDependencies ++= List(
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.libraryManagement,
      Dependencies.configDirectories,
      Dependencies.caseApp,
      Dependencies.sourcecode,
      Dependencies.log4jApi,
      Dependencies.log4jCore,
      Dependencies.sbtTestInterface,
      Dependencies.sbtTestAgent,
      Dependencies.directoryWatcher
    )
  )

import build.BuildImplementation.jvmOptions
// For the moment, the dependency is fixed
val frontend = project
  .dependsOn(backend, backend % "test->test")
  .enablePlugins(BuildInfoPlugin)
  .settings(testSettings)
  .settings(assemblySettings)
  .settings(
    name := s"bloop-frontend",
    mainClass in Compile in run := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := BloopInfoKeys,
    javaOptions in run ++= jvmOptions,
    javaOptions in Test ++= jvmOptions,
    libraryDependencies += Dependencies.graphviz % Test,
    fork in run := true,
    fork in Test := true,
    parallelExecution in test := false,
  )

val benchmarks = project
  .dependsOn(frontend % "compile->test", BenchmarkBridgeCompilation % "compile->jmh")
  .enablePlugins(BuildInfoPlugin, JmhPlugin)
  .settings(benchmarksSettings(frontend))

lazy val integrationsCore = project
  .in(file("integrations/core"))
  .settings(publishLocal := publishM2.value)

import build.BuildImplementation.BuildDefaults
lazy val sbtBloop = project
  .in(file("integrations/sbt-bloop"))
  .dependsOn(integrationsCore)
  .settings(
    name := "sbt-bloop",
    sbtPlugin := true,
    scalaVersion := {
      val orig = scalaVersion.value
      if ((sbtVersion in pluginCrossBuild).value.startsWith("0.13")) "2.10.6" else orig
    },
    BuildDefaults.scriptedSettings,
  )

val mavenBloop = project
  .in(file("integrations/maven-bloop"))
  .dependsOn(integrationsCore)
  .settings(
    name := "maven-bloop",
    mavenPlugin := true,
    publishLocal := publishM2.dependsOn(publishLocal in integrationsCore).value,
    libraryDependencies ++= List(
      Dependencies.mavenCore,
      Dependencies.mavenPluginApi,
      Dependencies.mavenPluginAnnotations,
    )
  )

val allProjects = Seq(backend, benchmarks, frontend, integrationsCore, sbtBloop, mavenBloop)
val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .aggregate(allProjectReferences: _*)
  .settings(
    skip in publish := true,
    crossSbtVersions := Seq("1.0.3", "0.13.16")
  )
