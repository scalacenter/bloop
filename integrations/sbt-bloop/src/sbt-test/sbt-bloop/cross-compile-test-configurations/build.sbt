import java.nio.file.Files

val foo = project
  .settings(
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5",
    sources.in(Test) += baseDirectory.in(ThisBuild).value./("StraySourceFile.scala")
  )

val bar = project.dependsOn(foo % "test->compile;test->test")
val baz = project.dependsOn(bar % "compile->test")
val woo = project.dependsOn(foo % "test->compile")

val allBloopConfigFiles = settingKey[List[File]]("All config files to test")
allBloopConfigFiles in ThisBuild := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val barConfig = bloopDir./("bar.json")
  val barTestConfig = bloopDir./("bar-test.json")
  val bazConfig = bloopDir./("baz.json")
  val bazTestConfig = bloopDir./("baz-test.json")
  val wooConfig = bloopDir./("woo.json")
  val wooTestConfig = bloopDir./("woo-test.json")
  List(
    fooConfig,
    fooTestConfig,
    barConfig,
    barTestConfig,
    bazConfig,
    bazTestConfig,
    wooConfig,
    wooTestConfig
  )
}

val checkBloopFile = taskKey[Unit]("Check bloop file contents")
checkBloopFile in ThisBuild := {
  val allConfigs = allBloopConfigFiles.value
  allConfigs.foreach(f => assert(Files.exists(f.toPath), s"Missing config file for ${f}."))
  val fooTestConfig = allConfigs.find(_.toString.endsWith("foo-test.json")).getOrElse(sys.error("Missing foo-test.json"))
  val fooTestConfigContents = new String(Files.readAllBytes(fooTestConfig.toPath))
  assert(fooTestConfigContents.contains("StraySourceFile.scala"), "Source file is missing in foo.")
}

val checkSourceAndDocs = taskKey[Unit]("Check source and doc jars are resolved and persisted")
checkSourceAndDocs in ThisBuild := {
  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  val allConfigs = allBloopConfigFiles.value
  val fooConfig = allConfigs.find(_.toString.endsWith("foo.json")).getOrElse(sys.error("Missing foo.json"))
  val fooConfigContents = readBareFile(fooConfig.toPath)
  assert(fooConfigContents.contains("\"classifier\":\"sources\""), "Missing source jar.")
  assert(fooConfigContents.contains("\"classifier\":\"javadoc\""), "Missing doc jar.")
}
