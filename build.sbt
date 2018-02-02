import build.BuildImplementation.BuildDefaults

/***************************************************************************************************/
/*                      This is the build definition of the source deps                            */
/***************************************************************************************************/

// Remember, `scripted` and `cachedPublishLocal` are defined here via aggregation
val bridgeIntegration = project
  .in(file(".bridge"))
  .aggregate(ZincBridge)
  .settings(
    releaseEarly := {()},
    skip in publish := true,
    // What the hell is going on here sbt? Why do you add scripted as a dep and you cannot resolve it?
    libraryDependencies := Nil,
    scalaVersion := (scalaVersion in ZincBridge).value,
    crossScalaVersions := (crossScalaVersions in ZincBridge).value,
  )

val zincIntegration = project
  .in(file(".zinc"))
  .aggregate(ZincRoot)
  .settings(
    releaseEarly := {()},
    skip in publish := true,
    // What the hell is going on here sbt? Why do you add scripted as a dep and you cannot resolve it?
    libraryDependencies := Nil,
    scalaVersion := (scalaVersion in ZincRoot).value,
    // This only covers 2.12 and 2.11, but this is enough.
    crossScalaVersions := (crossScalaVersions in ZincRoot).value,
  )

// Work around a sbt-scalafmt but that forces us to define `scalafmtOnCompile` in sourcedeps
val SbtConfig = com.lucidchart.sbt.scalafmt.ScalafmtSbtPlugin.autoImport.Sbt
val hijackScalafmtOnCompile = SettingKey[Boolean]("scalafmtOnCompile", "Just having fun.")
val nailgunIntegration = project
  .in(file(".nailgun"))
  .aggregate(NailgunServer)
  .settings(
    releaseEarly := {()},
    skip in publish := true,
    libraryDependencies := Nil,
    hijackScalafmtOnCompile in SbtConfig in NailgunBuild := false,
  )

val benchmarkBridge = project
  .in(file(".benchmark-bridge-compilation"))
  .aggregate(BenchmarkBridgeCompilation)
  .settings(
    releaseEarly := {()},
    skip in publish := true,
    libraryDependencies := Nil,
  )

val bspIntegration = project
  .in(file(".bsp"))
  .aggregate(Bsp)
  .settings(
    releaseEarly := {()},
    skip in publish := true,
    libraryDependencies := Nil,
  )

/***************************************************************************************************/
/*                            This is the build definition of the wrapper                          */
/***************************************************************************************************/
import build.Dependencies

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
  .dependsOn(Bsp)
  .enablePlugins(BuildInfoPlugin)
  .settings(testSettings)
  .settings(assemblySettings)
  .settings(releaseSettings)
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
      Dependencies.monix,
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

lazy val integrationsCore = project
  .in(file("integrations") / "core")
  .disablePlugins(sbt.ScriptedPlugin)
  .settings(
    name := "bloop-integrations-core",
    crossScalaVersions := List("2.12.4", "2.10.7"),
    // We compile in both so that the maven integration can be tested locally
    publishLocal := publishLocal.dependsOn(publishM2).value
  )

lazy val sbtBloop = project
  .in(file("integrations") / "sbt-bloop")
  .dependsOn(integrationsCore)
  .settings(
    name := "sbt-bloop",
    sbtPlugin := true,
    BuildDefaults.scriptedSettings,
    scalaVersion := BuildDefaults.fixScalaVersionForSbtPlugin.value,
  )

val mavenBloop = project
  .in(file("integrations") / "maven-bloop")
  .dependsOn(integrationsCore)
  .settings(name := "maven-bloop")
  .settings(BuildDefaults.mavenPluginBuildSettings)

val docs = project
  .in(file("website"))
  .enablePlugins(HugoPlugin)
  .settings(
    name := "bloop-website",
    skip in publish := true,
    sourceDirectory in Hugo := baseDirectory.value
    // baseURL in Hugo := uri("https://scala.epfl.ch"),
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

/***************************************************************************************************/
/*                      This is the corner for all the command definitions                         */
/***************************************************************************************************/
val publishLocalCmd = Keys.publishLocal.key.label
addCommandAlias(
  "setupIntegrations",
  List(
    s"+${integrationsCore.id}/$publishLocalCmd",
    s"^${sbtBloop.id}/$publishLocalCmd",
    s"${mavenBloop.id}/$publishLocalCmd"
  ).mkString(";", ";", "")
)

addCommandAlias(
  "runTests",
  List(
    s"${sbtBloop.id}/${scriptedAddSbtBloop.key.label}",
    s"${sbtBloop.id}/${scripted.key.label}"
  ).mkString(";", ";", "")
)

addCommandAlias(
  "install",
  Seq(
    s"+${bridgeIntegration.id}/$publishLocalCmd",
    s"+${zincIntegration.id}/$publishLocalCmd",
    s"${bspIntegration.id}/$publishLocalCmd",
    "setupIntegrations", // Reusing the previously defined command
    s"${nailgunIntegration.id}/$publishLocalCmd",
    s"${backend.id}/$publishLocalCmd",
    s"${frontend.id}/$publishLocalCmd"
  ).mkString(";", ";", "")
)

val releaseEarlyCmd = releaseEarly.key.label
val bloopModules = allProjectReferences
  .filterNot(_ == LocalProject(sbtBloop.id))
  .filterNot(_ == LocalProject(benchmarks.id))
val sourceProjects = List(bridgeIntegration, bspIntegration, nailgunIntegration)
val sourceModules = sourceProjects.map(m => LocalProject(m.id)) 
val actions = (bloopModules ++ sourceModules).map(m => s"+${m.project}/$releaseEarlyCmd")
// We don't need the zinc integration to be cross-published, Bloop is only 2.12.x
val extra = Seq(s"${zincIntegration.id}/$releaseEarlyCmd", s"^${sbtBloop.id}/$releaseEarlyCmd")
addCommandAlias("releaseBloop", (actions ++ extra).mkString(";", ";", ""))
