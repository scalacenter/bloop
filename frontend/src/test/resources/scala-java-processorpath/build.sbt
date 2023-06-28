bloopConfigDir in Global := baseDirectory.value / "bloop-config"

ThisBuild / scalaVersion := "2.12.18"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"

lazy val analyzerPath = taskKey[String]("get analyzer path")

lazy val `scala-java-processorpath` = (project in file("."))
  .settings(
    name := "scala-java-processorpath",
    analyzerPath := {
      def findPath(base: File): Seq[File] = {
        val finder: PathFinder = (base / "src") ** "FoobarValueAnalyzer.java"
        finder.get
      }

      val analyzerPath = findPath(baseDirectory.value)

      analyzerPath.head.getAbsolutePath
    }
  )

javacOptions ++= Seq("-processorpath", analyzerPath.value)
