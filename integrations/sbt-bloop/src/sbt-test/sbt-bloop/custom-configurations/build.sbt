import bloop.integrations.sbt.{BloopDefaults, BloopKeys}

val Custom = config("custom-it") extend Test

val foo = project
  .in(file(".") / "foo")
  .configs(IntegrationTest.extend(Test))
  .settings(Defaults.itSettings)

val bar = project
  .in(file(".") / "bar")
  .configs(Custom)
  .settings(
    inConfig(Custom)(
      Defaults.testSettings ++
        BloopDefaults.configSettings
    )
  )
  .dependsOn(foo % "custom-it->it;custom-it->test;test->test")

// Extended at the `.configs` site only, so the object reaching `inConfig` loses `extendsConfigs`.
lazy val Inline = config("inline-it")

val baz = project
  .in(file(".") / "baz")
  .configs(Inline.extend(Test))
  .settings(
    inConfig(Inline)(
      Defaults.testSettings ++
        BloopDefaults.configSettings
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
  val bazInlineTestConfig = bloopDir./("baz-inline-it.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val fooRuntimeConfig = bloopDir./("foo-runtime.json")
  val fooItConfig = bloopDir./("foo-it.json")
  val allConfigs = List(
    fooConfig,
    fooItConfig,
    fooTestConfig,
    barConfig,
    barTestConfig,
    barCustomTestConfig,
    bazInlineTestConfig
  )

  allConfigs.foreach(f => assert(Files.exists(f.toPath), s"Missing config file for ${f}."))
  assert(!Files.exists(fooRuntimeConfig.toPath), s"Configuration for runtime exists!")

  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  // Read foo-it config file, remove all whitespace
  val fooItConfigContents = readBareFile(fooItConfig.toPath)
  assert(
    fooItConfigContents.contains(""""dependencies":["foo-test","foo"]"""),
    "Dependency it->compile is missing in foo-it."
  )

  // Read foo-it config file, remove all whitespace
  val barItConfigContents = readBareFile(barCustomTestConfig.toPath)
  assert(
    // foo-it shows up, but not foo-test as it's implied by foo-it (IntegrationTest.extend(Test))
    barItConfigContents.contains(""""dependencies":["foo-it","bar","bar-test"]"""),
    "Dependency custom-it->test is missing in bar-custom-it."
  )

  // Bloop only runs tests for targets tagged `test` or `integration-test`.
  def assertTags(config: File, expected: String): Unit = {
    val contents = readBareFile(config.toPath)
    assert(
      contents.contains(s""""tags":["$expected"]"""),
      s"Expected tag $expected in ${config.getName}."
    )
  }

  assertTags(fooConfig, "library")
  assertTags(fooTestConfig, "test")
  assertTags(fooItConfig, "integration-test")
  assertTags(barCustomTestConfig, "test")
  assertTags(bazInlineTestConfig, "test")
}
