val foo = project
  .settings(sources.in(Test) += baseDirectory.in(ThisBuild).value./("StraySourceFile.scala"))
val bar = project.dependsOn(foo % "test->compile;test->test")
val baz = project.dependsOn(bar % "compile->test")
val woo = project.dependsOn(foo % "test->compile")

val checkBloopFile = taskKey[Unit]("Check bloop file contents")
checkBloopFile in ThisBuild := {
  import java.nio.file.Files
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val barConfig = bloopDir./("bar.json")
  val barTestConfig = bloopDir./("bar-test.json")
  val bazConfig = bloopDir./("baz.json")
  val bazTestConfig = bloopDir./("baz-test.json")
  val wooConfig = bloopDir./("woo.json")
  val wooTestConfig = bloopDir./("woo-test.json")
  val allConfigs = List(
    fooConfig,
    fooTestConfig,
    barConfig,
    barTestConfig,
    bazConfig,
    bazTestConfig,
    wooConfig,
    wooTestConfig
  )

  allConfigs.foreach(f => assert(Files.exists(f.toPath), s"Missing config file for ${f}."))
  val fooTestConfigContents = new String(Files.readAllBytes(fooTestConfig.toPath))
  assert(fooTestConfigContents.contains("StraySourceFile.scala"), "Source file is missing in foo.")
}
