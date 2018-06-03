import bloop.integrations.sbt.{BloopDefaults, BloopKeys}

val foo = project.in(file("."))
  .configs(IntegrationTest)
  .settings(inConfig(IntegrationTest)(
    Defaults.itSettings ++
      BloopDefaults.configSettings :+
      (BloopKeys.bloopDependencies := Seq(ThisProject % "test"))
  ))

val checkBloopFile = taskKey[Unit]("Check bloop file contents")
checkBloopFile in ThisBuild := {
  import java.nio.file.Files
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val fooItConfig = bloopDir./("foo-it.json")
  val allConfigs = List(
    fooConfig,
    fooItConfig,
    fooTestConfig
  )

  allConfigs.foreach(f => assert(Files.exists(f.toPath), s"Missing config file for ${f}."))

  // Read foo-it config file, remove all whitespace
  val fooItConfigContents = new String(Files.readAllBytes(fooItConfig.toPath)).replaceAll("\\s", "")
  assert(fooItConfigContents.contains(""""dependencies":["foo-test"]"""), "Dependency it->test is missing in foo.")
}
