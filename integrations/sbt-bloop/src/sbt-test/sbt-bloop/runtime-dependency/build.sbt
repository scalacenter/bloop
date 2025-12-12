import bloop.integrations.sbt.BloopDefaults

val runtimeDependency = project
  .in(file("."))
  .settings(
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.7" % Runtime
  )

val bloopConfigFile = settingKey[File]("Config file to test")
bloopConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("runtimeDependency.json")
  config
}

val bloopTestConfigFile = settingKey[File]("Test config file to test")
bloopTestConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("runtimeDependency-test.json")
  config
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  if (Keys.name.value != "runtimeDependency") {
    val configContents = BloopDefaults.unsafeParseConfig(bloopConfigFile.value.toPath)

    assert(configContents.project.platform.isDefined)
    val platformJvm =
      configContents.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm]
    val obtainedRuntimeClasspath = platformJvm.classpath.map(_.map(_.getFileName.toString))
    val expectedRuntimeClasspath = Some(
      List(
        "classes",
        "scala-library.jar",
        "logback-classic-1.2.7.jar",
        "logback-core-1.2.7.jar",
        "slf4j-api-1.7.32.jar"
      )
    )
    assert(obtainedRuntimeClasspath == expectedRuntimeClasspath)

    assert(
      configContents.project.classpath.map(_.getFileName.toString) == List("scala-library.jar")
    )

    val configTestContents = BloopDefaults.unsafeParseConfig(bloopTestConfigFile.value.toPath)
    assert(configTestContents.project.platform.isDefined)
    val testPlatformJvm =
      configTestContents.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm]
    assert(testPlatformJvm.classpath.isEmpty)

    val obtainedTestClasspath = configTestContents.project.classpath.map(_.getFileName.toString)
    val expectedTestClasspath =
      List(
        "classes",
        "scala-library.jar",
        "logback-classic-1.2.7.jar",
        "logback-core-1.2.7.jar",
        "slf4j-api-1.7.32.jar"
      )

    assert(obtainedTestClasspath == expectedTestClasspath)
  }
}
