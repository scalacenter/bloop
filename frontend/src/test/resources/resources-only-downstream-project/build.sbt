Global / bloopConfigDir := baseDirectory.value / "bloop-config"

ThisBuild / scalaVersion := "3.5.0"

lazy val projectA = project.in(file("project-a"))
lazy val projectAj = project.in(file("project-aj"))

lazy val projectB = project
  .in(file("project-b"))
  .dependsOn(projectA, projectAj)
