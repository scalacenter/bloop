import build.BuildImplementation.BuildDefaults

// Tell bloop to aggregate source deps (benchmark) config files in the same bloop config dir
bloopAggregateSourceDependencies in Global := true

/***************************************************************************************************/
/*                      This is the build definition of the source deps                            */
/***************************************************************************************************/
val benchmarkBridge = project
  .in(file(".benchmark-bridge-compilation"))
  .aggregate(BenchmarkBridgeCompilation)
  .disablePlugins(ScriptedPlugin)
  .settings(
    releaseEarly := { () },
    skip in publish := true
  )

/***************************************************************************************************/
/*                            This is the build definition of the wrapper                          */
/***************************************************************************************************/
import build.Dependencies

val backend = project
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-backend",
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := BloopBackendInfoKeys,
    buildInfoObject := "BloopScalaInfo",
    libraryDependencies ++= List(
      Dependencies.zinc,
      Dependencies.nailgun,
      Dependencies.scalazCore,
      Dependencies.scalazConcurrent,
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.coursierScalaz,
      Dependencies.libraryManagement,
      Dependencies.configDirectories,
      Dependencies.sourcecode,
      Dependencies.sbtTestInterface,
      Dependencies.sbtTestAgent,
      Dependencies.monix,
      Dependencies.directoryWatcher
    )
  )

// Needs to be called `jsonConfig` because of naming conflict with sbt universe...
val jsonConfig = project
  .in(file("config"))
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-config",
    crossScalaVersions := List(Keys.scalaVersion.in(backend).value, "2.10.7"),
    // We compile in both so that the maven integration can be tested locally
    publishLocal := publishLocal.dependsOn(publishM2).value,
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.12") {
        List(
          Dependencies.typesafeConfig,
          Dependencies.metaconfigCore,
          Dependencies.metaconfigDocs,
          Dependencies.metaconfigConfig,
          Dependencies.circeDerivation,
          Dependencies.nuprocess,
          Dependencies.scalacheck % Test,
        )
      } else {
        List(
          Dependencies.typesafeConfig,
          Dependencies.circeCore,
          Dependencies.circeGeneric,
          compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
          Dependencies.scalacheck % Test,
        )
      }
    }
  )

import build.BuildImplementation.jvmOptions
// For the moment, the dependency is fixed
lazy val frontend: Project = project
  .dependsOn(backend, backend % "test->test", jsonConfig)
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(testSettings, assemblySettings, releaseSettings, integrationTestSettings)
  .settings(
    name := s"bloop-frontend",
    mainClass in Compile in run := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := BloopInfoKeys(nativeBridge),
    javaOptions in run ++= jvmOptions,
    javaOptions in Test ++= jvmOptions,
    libraryDependencies += Dependencies.graphviz % Test,
    fork in run := true,
    fork in Test := true,
    parallelExecution in test := false,
    libraryDependencies ++= List(
      Dependencies.scalazCore,
      Dependencies.bsp,
      Dependencies.monix,
      Dependencies.caseApp,
      Dependencies.ipcsocket % Test
    ),
    dependencyOverrides += Dependencies.shapeless
  )

val benchmarks = project
  .dependsOn(frontend % "compile->test", BenchmarkBridgeCompilation % "compile->jmh")
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin, JmhPlugin)
  .settings(benchmarksSettings(frontend))
  .settings(
    skip in publish := true,
  )

lazy val sbtBloop = project
  .enablePlugins(ScriptedPlugin)
  .in(file("integrations") / "sbt-bloop")
  .dependsOn(jsonConfig)
  .settings(BuildDefaults.scriptedSettings)
  .settings(
    name := "sbt-bloop",
    sbtPlugin := true,
    scalaVersion := BuildDefaults.fixScalaVersionForSbtPlugin.value,
    bintrayPackage := "sbt-bloop",
    bintrayOrganization := Some("sbt"),
    bintrayRepository := "sbt-plugin-releases",
    publishMavenStyle := releaseEarlyWith.value == SonatypePublisher,
    publishLocal := publishLocal.dependsOn(publishLocal in jsonConfig).value
  )

val mavenBloop = project
  .in(file("integrations") / "maven-bloop")
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig)
  .settings(name := "maven-bloop")
  .settings(BuildDefaults.mavenPluginBuildSettings)

val docs = project
  .in(file("website"))
  .enablePlugins(HugoPlugin, GhpagesPlugin, ScriptedPlugin)
  .settings(
    name := "bloop-website",
    skip in publish := true,
    websiteSettings
  )

lazy val nativeBridge = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scala-native")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-native-bridge",
    libraryDependencies += Dependencies.scalaNativeTools
  )

val allProjects = Seq(backend, benchmarks, frontend, jsonConfig, sbtBloop, mavenBloop, nativeBridge)
val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .disablePlugins(ScriptedPlugin)
  .aggregate(allProjectReferences: _*)
  .settings(
    releaseEarly := { () },
    skip in publish := true,
    crossSbtVersions := Seq("1.1.0", "0.13.16")
  )

/***************************************************************************************************/
/*                      This is the corner for all the command definitions                         */
/***************************************************************************************************/
val publishLocalCmd = Keys.publishLocal.key.label

// Runs the scripted tests to setup integration tests
// ! This is used by the benchmarks too !
addCommandAlias(
  "install",
  Seq(
    s"+${jsonConfig.id}/$publishLocalCmd",
    s"^${sbtBloop.id}/$publishLocalCmd",
    s"${mavenBloop.id}/$publishLocalCmd",
    s"${backend.id}/$publishLocalCmd",
    s"${frontend.id}/$publishLocalCmd",
    s"${nativeBridge.id}/$publishLocalCmd"
  ).mkString(";", ";", "")
)

val releaseEarlyCmd = releaseEarly.key.label

val allBloopReleases = List(
  s"${backend.id}/$releaseEarlyCmd",
  s"${frontend.id}/$releaseEarlyCmd",
  s"+${jsonConfig.id}/$publishLocalCmd", // Necessary because of a coursier bug?
  s"+${jsonConfig.id}/$releaseEarlyCmd",
  s"^${sbtBloop.id}/$releaseEarlyCmd",
  s"${mavenBloop.id}/$releaseEarlyCmd",
  s"${nativeBridge.id}/$releaseEarlyCmd"
)

val allReleaseActions = allBloopReleases ++ List("sonatypeReleaseAll")
addCommandAlias("releaseBloop", allReleaseActions.mkString(";", ";", ""))
