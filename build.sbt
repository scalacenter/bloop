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
    libraryDependencies ++= List(
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.libraryManagement,
      Dependencies.configDirectories,
      Dependencies.caseApp,
      Dependencies.sourcecode,
      Dependencies.log4jApi,
      Dependencies.log4jCore,
      Dependencies.sbtTestInterface
    )
  )

// For the moment, the dependency is fixed
val frontend = project
  .dependsOn(backend)
  .enablePlugins(BuildInfoPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop",
    mainClass in Compile in run := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := BloopInfoKeys,
    fork in run := true,
    javaOptions in run ++= Seq("-Xmx4g", "-Xms2g"),
    libraryDependencies += Dependencies.graphviz % Test
  )

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

val allProjects = Seq(backend, frontend, sbtBloop)
val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .aggregate(allProjectReferences: _*)
  .settings(crossSbtVersions := Seq("1.0.3", "0.13.16"))
