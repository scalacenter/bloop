import bloop.integrations.sbt.BloopDefaults

val nonCompiling = project
  .in(file("."))

val bloopConfigFile = settingKey[File]("Config file to test")
bloopConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("nonCompiling.json")
  config
}

val bloopTestConfigFile = settingKey[File]("Test config file to test")
bloopTestConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("nonCompiling-test.json")
  config
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  if (Keys.name.value != "nonCompiling") {
    val configContents = BloopDefaults.unsafeParseConfig(bloopConfigFile.value.toPath)
    assert(configContents.project.platform.isDefined)

    val configTestContents = BloopDefaults.unsafeParseConfig(bloopTestConfigFile.value.toPath)
    assert(configTestContents.project.platform.isDefined)
  }
}
