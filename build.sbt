val sharedSettings: Seq[Setting[_]] = Seq(
  organization := "ch.epfl.scala",
  version := "0.1.0-SNAPSHOT"
)

val blossomSettings = sharedSettings ++ Seq(
  scalaVersion := "2.12.4"
)

lazy val blossom = project
  .in(file("."))
  .settings(sharedSettings)
  .aggregate(allProjectReferences: _*)

val ZincProject = RootProject(
  uri("git://github.com/scalacenter/zinc.git#a90a0e98f5be965452261388050debe5a7396dac")
)
val Zinc = ProjectRef(ZincProject.build, "zinc")

lazy val compiler = project
  .dependsOn(Zinc)
  .in(file("compiler"))
  .settings(
    blossomSettings,
    name := "compiler",
    libraryDependencies ++= List(
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.libraryManagement,
    ),
    fork in run := true,
    connectInput in run := true,
    javaOptions in run ++= Seq("-Xmx4g", "-Xms2g")
  )

lazy val sbtBlossom = project
  .in(file("sbt-blossom"))
  .settings(
    sharedSettings,
    name := "sbt-blossom",
    sbtPlugin := true,
    crossSbtVersions := Seq("0.13.16", "1.0.3")
  )

lazy val allProjects          = Seq(compiler, sbtBlossom)
lazy val allProjectReferences = allProjects.map(p => LocalProject(p.id))
