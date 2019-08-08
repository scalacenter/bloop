import Dependencies._

bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"

ThisBuild / scalaVersion := "2.12.9"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "Scala Seed Project",
    libraryDependencies += scalaTest % Test,
    fork in Test := true
  )
