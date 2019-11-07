import bloop.integrations.sbt.BloopDefaults

val foo = project
val bar = project.dependsOn(foo % Provided)
val baz = project.in(file(".")).dependsOn(bar % "provided->test;test->provided")

val allBloopConfigFiles = settingKey[List[File]]("All config files to test")
allBloopConfigFiles in ThisBuild := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val barConfig = bloopDir./("bar.json")
  val barTestConfig = bloopDir./("bar-test.json")
  val bazConfig = bloopDir./("baz.json")
  val bazTestConfig = bloopDir./("baz-test.json")
  List(fooConfig, fooTestConfig, barConfig, barTestConfig, bazConfig, bazTestConfig)
}

def readConfigFor(projectName: String, allConfigs: Seq[File]): bloop.config.Config.File = {
  val configFile = allConfigs
    .find(_.toString.endsWith(s"$projectName.json"))
    .getOrElse(sys.error(s"Missing $projectName.json"))
  BloopDefaults.unsafeParseConfig(configFile.toPath)
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles in ThisBuild := {
  val allConfigs = allBloopConfigFiles.value
  val barConfigContents = readConfigFor("bar", allConfigs)
  assert(barConfigContents.project.dependencies == List("foo"))

  val bazTestConfigContents = readConfigFor("baz-test", allConfigs)
  assert(bazTestConfigContents.project.dependencies.sorted == List("bar", "baz"))

  val bazConfigContents = readConfigFor("baz", allConfigs)
  assert(bazConfigContents.project.dependencies.sorted == List("bar-test"))
}
