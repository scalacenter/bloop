val foo = project.in(file("."))

val bar = project.settings(
  fork in run := true,
  fork in Test := true
)

val baz = project

def readConfigFor(projectName: String, allConfigs: Seq[File]): bloop.config.Config.File = {
  val configFile = allConfigs
    .find(_.toString.endsWith(s"$projectName.json"))
    .getOrElse(sys.error(s"Missing $projectName.json"))
  bloop.integrations.sbt.BloopDefaults.unsafeParseConfig(configFile.toPath)
}

val allBloopConfigFiles = settingKey[List[File]]("All config files to test")
allBloopConfigFiles in ThisBuild := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val barConfig = bloopDir./("bar.json")
  val barTestConfig = bloopDir./("bar-test.json")
  val bazConfig = bloopDir./("baz.json")
  val bazTestConfig = bloopDir./("baz-test.json")
  List(
    fooConfig,
    fooTestConfig,
    barConfig,
    barTestConfig,
    bazConfig,
    bazTestConfig
  )
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles in ThisBuild := {
  import java.nio.file.Files
  val allConfigs = allBloopConfigFiles.value

  val fooConfig = readConfigFor("foo", allConfigs)
  val fooTestConfig = readConfigFor("foo-test", allConfigs)
  val barConfig = readConfigFor("bar", allConfigs)
  val barTestConfig = readConfigFor("bar-test", allConfigs)
  val bazConfig = readConfigFor("baz", allConfigs)
  val bazTestConfig = readConfigFor("baz-test", allConfigs)

  def readJvmOptions(config: bloop.config.Config.File): List[String] = {
    config.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm].config.options
  }

  val userDir = sys.props.get("user.dir").getOrElse("working-dir")
  assert(
    readJvmOptions(fooConfig)
      .exists(opt => opt.startsWith("-Duser.dir") && opt.endsWith(userDir)),
    s"foo working directory ends with $userDir"
  )

  assert(
    readJvmOptions(fooTestConfig)
      .exists(opt => opt.startsWith("-Duser.dir") && opt.endsWith(userDir)),
    s"foo test working directory ends with $userDir"
  )

  assert(
    readJvmOptions(barConfig)
      .exists(opt => opt.startsWith("-Duser.dir") && opt.endsWith("bar")),
    "bar working directory ends with bar"
  )

  assert(
    readJvmOptions(barTestConfig)
      .exists(opt => opt.startsWith("-Duser.dir") && opt.endsWith("bar")),
    "bar test working directory ends with bar"
  )

  assert(
    readJvmOptions(bazConfig)
      .exists(opt => opt.startsWith("-Duser.dir") && opt.endsWith(userDir)),
    s"baz working directory ends with $userDir"
  )

  assert(
    readJvmOptions(bazTestConfig)
      .exists(opt => opt.startsWith("-Duser.dir") && opt.endsWith(userDir)),
    s"baz test working directory ends with $userDir"
  )
}
