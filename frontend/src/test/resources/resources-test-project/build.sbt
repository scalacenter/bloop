import Dependencies._
Global / bloopConfigDir := baseDirectory.value / "bloop-config"

ThisBuild / scalaVersion := "2.12.21"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "resources-test-project",
    libraryDependencies += munit % Test,
    Test / fork := true
  )
