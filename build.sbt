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
    "frontend/publishLocal"
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
      Dependencies.directoryWatcher
    )
  )

// For the moment, the dependency is fixed
val frontend = project
  .dependsOn(backend)
  .enablePlugins(BuildInfoPlugin)
  .settings(testSettings)
  .settings(assemblySettings)
  .settings(
    name := "bloop-frontend",
    mainClass in Compile in run := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := BloopInfoKeys,
    javaOptions in run ++= Seq("-Xmx4g", "-Xms2g"),
    libraryDependencies += Dependencies.graphviz % Test,
    fork in run := true,
    fork in Test := true,
    parallelExecution in test := false,
  )

val benchmarks = project
  .dependsOn(frontend % "compile->test", BenchmarkBridgeCompilation % "compile->jmh")
  .enablePlugins(JmhPlugin)
  .settings(benchmarksSettings)

import build.BuildImplementation.BuildDefaults
lazy val sbtBloop = project
  .in(file("sbt-bloop"))
  .settings(
    name := "sbt-bloop",
    sbtPlugin := true,
    scalaVersion := {
      val orig = scalaVersion.value
      if ((sbtVersion in pluginCrossBuild).value.startsWith("0.13")) "2.10.6" else orig
    },
    // The scripted projects to setup are in the test resources of frontend
    BuildDefaults.scriptedSettings(resourceDirectory in Test in frontend),
  )

val allProjects = Seq(backend, benchmarks, frontend, sbtBloop)
val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .aggregate(allProjectReferences: _*)
  .settings(
    skip in publish := true,
    crossSbtVersions := Seq("1.0.3", "0.13.16")
  )

