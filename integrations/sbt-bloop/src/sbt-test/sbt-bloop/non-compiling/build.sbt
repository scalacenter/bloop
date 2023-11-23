import bloop.integrations.sbt.BloopDefaults

name := "non-compiling"

val bloopConfigFile = settingKey[File]("Config file to test")
ThisBuild / bloopConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("non-compiling.json")
  config
}

val bloopTestConfigFile = settingKey[File]("Test config file to test")
ThisBuild / bloopTestConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("non-compiling-test.json")
  config
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
ThisBuild / checkBloopFiles := {
  val configContents = BloopDefaults.unsafeParseConfig(bloopConfigFile.value.toPath)
  assert(configContents.project.platform.isDefined)

  val configTestContents = BloopDefaults.unsafeParseConfig(bloopTestConfigFile.value.toPath)
  assert(configTestContents.project.platform.isDefined)
}
