bloopConfigDir in Global := baseDirectory.value / "bloop-config"
lazy val framework = project
  .in(file("framework"))
  .settings(
    libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0"
  )

lazy val test = project
  .in(file("test"))
  .dependsOn(framework)
  .settings(
    testFrameworks += TestFramework("foo.Framework")
  )
