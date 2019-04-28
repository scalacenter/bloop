import bloop.integrations.sbt.{BloopDefaults, BloopKeys}

val Custom = config("custom-it") extend Test

val foo = project
  .in(file("."))
  .configs(IntegrationTest)
  .settings(
    inConfig(IntegrationTest)(
      Defaults.itSettings ++
        BloopDefaults.configSettings(IntegrationTest)
    )
  )

val bar = project
  .in(file(".") / "bar")
  .configs(Custom)
  .settings(
    inConfig(Custom)(
      BloopDefaults.configSettings(Custom)
    )
  )

val checkBloopFile = taskKey[Unit]("Check bloop file contents")
checkBloopFile in ThisBuild := {
  import java.nio.file.Files
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val barConfig = bloopDir./("bar.json")
  val barTestConfig = bloopDir./("bar-test.json")
  val barCustomTestConfig = bloopDir./("bar-custom-it.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val fooRuntimeConfig = bloopDir./("foo-runtime.json")
  val fooItConfig = bloopDir./("foo-it.json")
  val allConfigs = List(
    fooConfig,
    fooItConfig,
    fooTestConfig,
    barConfig,
    barTestConfig,
    barCustomTestConfig
  )

  allConfigs.foreach(f => assert(Files.exists(f.toPath), s"Missing config file for ${f}."))
  assert(!Files.exists(fooRuntimeConfig.toPath), s"Configuration for runtime exists!")

  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  // Read foo-it config file, remove all whitespace
  val fooItConfigContents = readBareFile(fooItConfig.toPath)
  assert(
    fooItConfigContents.contains(""""dependencies":["foo"]"""),
    "Dependency it->compile is missing in foo-it."
  )

  // Read foo-it config file, remove all whitespace
  val barItConfigContents = readBareFile(barCustomTestConfig.toPath)
  assert(
    barItConfigContents.contains(""""dependencies":["bar-test","bar"]"""),
    "Dependency custom-it->test is missing in bar-custom-it."
  )
}
