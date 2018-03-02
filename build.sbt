import build.BuildImplementation.BuildDefaults

// Tell bloop to aggregate source deps (benchmark) config files in the same bloop config dir
bloopAggregateSourceDependencies in Global := true

/***************************************************************************************************/
/*                      This is the build definition of the source deps                            */
/***************************************************************************************************/
val benchmarkBridge = project
  .in(file(".benchmark-bridge-compilation"))
  .aggregate(BenchmarkBridgeCompilation)
  .settings(
    releaseEarly := { () },
    skip in publish := true
  )

/***************************************************************************************************/
/*                            This is the build definition of the wrapper                          */
/***************************************************************************************************/
import build.Dependencies

val backend = project
  .settings(testSettings)
  .settings(
    name := "bloop-backend",
    libraryDependencies ++= List(
      Dependencies.zinc,
      Dependencies.nailgun,
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.libraryManagement,
      Dependencies.configDirectories,
      Dependencies.sourcecode,
      Dependencies.sbtTestInterface,
      Dependencies.sbtTestAgent,
      Dependencies.monix,
      Dependencies.directoryWatcher
    )
  )

// Needs to be called `configModule` because of naming conflict with sbt universe...
val configModule = project
  .in(file("config"))
  .settings(testSettings)
  .settings(
    name := "bloop-config",
    crossScalaVersions := List(Keys.scalaVersion.in(backend).value, "2.10.7"),
    // We compile in both so that the maven integration can be tested locally
    publishLocal := publishLocal.dependsOn(publishM2).value,
    libraryDependencies ++= List(
      Dependencies.typesafeConfig,
      Dependencies.metaconfigCore,
      Dependencies.metaconfigDocs,
      Dependencies.metaconfigConfig,
      Dependencies.scalacheck % Test,
      Dependencies.circeConfig % Test,
      Dependencies.circeDerivation % Test,
    )
  )

import build.BuildImplementation.jvmOptions
// For the moment, the dependency is fixed
val frontend = project
  .dependsOn(backend, backend % "test->test", configModule)
  .enablePlugins(BuildInfoPlugin)
  .settings(testSettings, assemblySettings, releaseSettings, integrationTestSettings)
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
    libraryDependencies ++= List(
      Dependencies.bsp,
      Dependencies.monix,
      Dependencies.caseApp,
      Dependencies.ipcsocket % Test
    )
  )

val benchmarks = project
  .dependsOn(frontend % "compile->test", BenchmarkBridgeCompilation % "compile->jmh")
  .enablePlugins(BuildInfoPlugin, JmhPlugin)
  .settings(benchmarksSettings(frontend))
  .settings(
    skip in publish := true,
  )

lazy val sbtBloop = project
  .in(file("integrations") / "sbt-bloop")
  .dependsOn(configModule)
  .settings(
    name := "sbt-bloop",
    sbtPlugin := true,
    scalaVersion := BuildDefaults.fixScalaVersionForSbtPlugin.value,
    bintrayPackage := "sbt-bloop",
    bintrayOrganization := Some("sbt"),
    bintrayRepository := "sbt-plugin-releases",
    publishMavenStyle := releaseEarlyWith.value == SonatypePublisher
  )

val mavenBloop = project
  .in(file("integrations") / "maven-bloop")
  .dependsOn(configModule)
  .settings(name := "maven-bloop")
  .settings(BuildDefaults.mavenPluginBuildSettings)

val docs = project
  .in(file("website"))
  .enablePlugins(HugoPlugin, GhpagesPlugin)
  .settings(
    name := "bloop-website",
    skip in publish := true,
    websiteSettings
  )

val allProjects = Seq(backend, benchmarks, frontend, configModule, sbtBloop, mavenBloop)
val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
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
    s"+${configModule.id}/$publishLocalCmd",
    s"^${sbtBloop.id}/$publishLocalCmd",
    s"${mavenBloop.id}/$publishLocalCmd",
    s"${backend.id}/$publishLocalCmd",
    s"${frontend.id}/$publishLocalCmd"
  ).mkString(";", ";", "")
)

val releaseEarlyCmd = releaseEarly.key.label

val allBloopReleases = List(
  s"${backend.id}/$releaseEarlyCmd",
  s"${frontend.id}/$releaseEarlyCmd",
  s"+${configModule.id}/$publishLocalCmd", // Necessary because of a coursier bug?
  s"+${configModule.id}/$releaseEarlyCmd",
  s"^${sbtBloop.id}/$releaseEarlyCmd",
  s"${mavenBloop.id}/$releaseEarlyCmd",
)

val allReleaseActions = allBloopReleases ++ List("sonatypeReleaseAll")
addCommandAlias("releaseBloop", allReleaseActions.mkString(";", ";", ""))
