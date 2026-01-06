import bloop.integrations.sbt.BloopDefaults

val providedRuntimeClasspath = project
  .in(file("."))
  .settings(
    libraryDependencies += "com.google.code.gson" % "gson" % "2.10" % Provided
  )

val bloopConfigFile = settingKey[File]("Config file to test")
bloopConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  bloopDir./("providedRuntimeClasspath.json")
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  if (Keys.name.value != "providedRuntimeClasspath") {
    val configContents = BloopDefaults.unsafeParseConfig(bloopConfigFile.value.toPath)

    assert(configContents.project.platform.isDefined)
    val platformJvm =
      configContents.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm]
    val runtimeClasspathNames = platformJvm.classpath.map(_.map(_.getFileName.toString))

    assert(
      runtimeClasspathNames.exists(_.contains("gson-2.10.jar")),
      s"Provided dependency gson should be in runtime classpath, got: $runtimeClasspathNames"
    )

    assert(
      configContents.project.classpath.map(_.getFileName.toString).contains("gson-2.10.jar"),
      "Provided dependency gson should be in compile classpath"
    )
  }
}
