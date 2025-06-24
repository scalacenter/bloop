Global / bloopConfigDir := baseDirectory.value / "bloop-config"

ThisBuild / scalaVersion := "3.5.0"

lazy val projectA = project.in(file("project-a"))

lazy val projectB = project
  .in(file("project-b"))
  .dependsOn(projectA)
  .settings(
    Compile / unmanagedResourceDirectories ++= (
      projectA / Compile / unmanagedResourceDirectories
    ).value
  )
