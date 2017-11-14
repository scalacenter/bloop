/***************************************************************************************************/
/*                            This is the build definition of the wrapper                          */
/***************************************************************************************************/
val blossom = project
  .in(file("."))
  .aggregate(allProjectReferences: _*)
  .settings(
    crossSbtVersions := Seq("1.0.3", "0.13.16")
  )

lazy val compiler = project
  .dependsOn(Zinc)
  .settings(
    fork in run := true,
    connectInput in run := true,
    javaOptions in run ++= Seq("-Xmx4g", "-Xms2g"),
    libraryDependencies ++= List(
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.libraryManagement,
    )
  )

lazy val sbtBlossom = project
  .in(file("sbt-blossom"))
  .settings(
    scalaVersion := {
      val orig = scalaVersion.value
      if ((sbtVersion in pluginCrossBuild).value.startsWith("0.13")) "2.10.6" else orig
    },
    sbtPlugin := true
  )

lazy val allProjects          = Seq(compiler, sbtBlossom)
lazy val allProjectReferences = allProjects.map(p => LocalProject(p.id))

/***************************************************************************************************/
/*                      This is the build definition of the zinc integration                       */
/***************************************************************************************************/
// Remember, `scripted` and `cachedPublishLocal` are defined here via aggregation
val bridgeIntegration = project
  .in(file(".bridge"))
  .aggregate(ZincBridge)
  .settings(
    scalaVersion := (scalaVersion in ZincBridge).value,
    crossScalaVersions := (crossScalaVersions in ZincBridge).value,
  )

val zincIntegration = project
  .in(file(".zinc"))
  .aggregate(Zinc)
  .settings(
    scalaVersion := (scalaVersion in Zinc).value,
    // This only covers 2.12 and 2.11, but this is enough.
    crossScalaVersions := (crossScalaVersions in Zinc).value,
    // Requires at least to cross publish the bridges
    test := (test in Test in ZincRoot).dependsOn(test in Test in ZincBridge).value,
  )

// Work around a sbt-scalafmt but that forces us to define `scalafmtOnCompile` in sourcedeps
val SbtConfig               = com.lucidchart.sbt.scalafmt.ScalafmtSbtPlugin.autoImport.Sbt
val hijackScalafmtOnCompile = SettingKey[Boolean]("scalafmtOnCompile", "Just having fun.")
val zincNailgun = project
  .in(file(".nailgun"))
  .aggregate(NailgunServer)
  .settings(
    hijackScalafmtOnCompile in SbtConfig in NailgunBuild := false,
  )
