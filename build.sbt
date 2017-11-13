val blossom = project
  .in(file("."))
  .aggregate(allProjectReferences: _*)

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
    sbtPlugin := true,
    crossSbtVersions := Seq("0.13.16", "1.0.3")
  )

lazy val allProjects          = Seq(compiler, sbtBlossom)
lazy val allProjectReferences = allProjects.map(p => LocalProject(p.id))
