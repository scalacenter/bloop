val sharedSettings: Seq[Setting[_]] = Seq(
  organization := "org.scalacenter",
  version := "0.1.0-SNAPSHOT"
)

val blossomSettings = sharedSettings ++ Seq(
  scalaVersion := "2.12.4"
)

lazy val blossom = project
  .in(file("."))
  .settings(sharedSettings)
  .aggregate(allProjectReferences: _*)

lazy val compiler = project
  .in(file("compiler"))
  .settings(
    blossomSettings,
    name := "compiler",
    libraryDependencies += Dependencies.zinc,
    libraryDependencies += Dependencies.libraryManagement,
    libraryDependencies += Dependencies.coursier,
    libraryDependencies += Dependencies.coursierCache
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
