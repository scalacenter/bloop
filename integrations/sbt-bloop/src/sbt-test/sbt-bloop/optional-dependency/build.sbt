import bloop.integrations.sbt.BloopDefaults

val foo = project
val bar = project.dependsOn(foo % Optional)
val baz = project.dependsOn(bar)

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
  assert(
    barConfigContents.project.classpath.exists(_.toString.contains("/foo")),
    barConfigContents.project.classpath.mkString("\n")
  )
  assert(
    barConfigContents.project.dependencies == List("foo"),
    barConfigContents.project.dependencies
  )

  val bazConfigContents = readConfigFor("baz", allConfigs)
  assert(
    bazConfigContents.project.classpath.exists(_.toString.contains("/bar")),
    bazConfigContents.project.classpath.mkString("\n")
  )
  assert(
    !bazConfigContents.project.classpath.exists(_.toString.contains("/foo")),
    bazConfigContents.project.classpath.mkString("\n")
  )
  assert(
    bazConfigContents.project.dependencies.sorted == List("bar"),
    bazConfigContents.project.dependencies.sorted
  )
}
