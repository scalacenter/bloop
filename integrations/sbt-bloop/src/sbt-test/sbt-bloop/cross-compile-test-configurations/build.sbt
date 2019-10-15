import java.nio.file.{Files, Path}
import bloop.integrations.sbt.BloopDefaults

val foo = project
  .settings(
    libraryDependencies ++= List(
      "org.scalatest" %% "scalatest" % "3.0.5",
      "io.circe" %% "circe-core" % "0.9.3",
      "io.circe" %% "circe-generic" % "0.9.3",
      "org.scalameta" %% "scalameta" % "4.0.0-M2"
    ),
    sources.in(Test) += baseDirectory.in(ThisBuild).value./("StraySourceFile.scala")
  )

val bar = project.dependsOn(foo % "test->compile;test->test")
val baz = project.dependsOn(bar % "compile->test")
val woo = project.dependsOn(foo % "test->compile")
val yay = project.dependsOn(foo)
val zee = project.configs(IntegrationTest).dependsOn(foo % "it->test")

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
  val yayConfig = bloopDir./("yay.json")
  val yayTestConfig = bloopDir./("yay-test.json")
  val zeeConfig = bloopDir./("zee.json")
  val zeeTestConfig = bloopDir./("zee-test.json")
  val zeeItConfig = bloopDir./("zee-it.json")
  List(
    fooConfig,
    fooTestConfig,
    barConfig,
    barTestConfig,
    bazConfig,
    bazTestConfig,
    wooConfig,
    wooTestConfig,
    yayConfig,
    yayTestConfig,
    zeeConfig,
    zeeTestConfig,
    zeeItConfig
  )
}

def readConfigFor(projectName: String, allConfigs: Seq[File]): bloop.config.Config.File = {
  val configFile = allConfigs
    .find(_.toString.endsWith(s"$projectName.json"))
    .getOrElse(sys.error(s"Missing $projectName.json"))
  BloopDefaults.parseConfig(configFile.toPath)
}

val checkBloopFile = taskKey[Unit]("Check bloop file contents")
checkBloopFile in ThisBuild := {
  val allConfigs = allBloopConfigFiles.value
  allConfigs.foreach(f => assert(Files.exists(f.toPath), s"Missing config file for ${f}."))

  // Test that independent source files are correctly saved by the plugin extractor
  val fooTestConfigContents = readConfigFor("foo-test", allConfigs)
  assert(
    fooTestConfigContents.project.sources.exists(_.toString.contains("StraySourceFile.scala")),
    "Source file is missing in foo."
  )

  val barConfigContents = readConfigFor("bar", allConfigs)
  assert(barConfigContents.project.dependencies.sorted == List())

  // Test that 'yay-test' does not add a dependency to 'foo-test' without the "test->test" configuration
  // Default if no configuration is dependency to `Compile` (double checked by '-> yay/test:compile')
  val yayTestConfigContents = readConfigFor("yay-test", allConfigs)
  assert(yayTestConfigContents.project.dependencies.sorted == List("foo", "yay"))

  // Test that zee-it contains a dependency to foo-test
  val zeeItConfigContents = readConfigFor("zee-it", allConfigs)
  assert(zeeItConfigContents.project.dependencies.sorted == List("foo-test", "zee"))
}

val checkSourceAndDocs = taskKey[Unit]("Check source and doc jars are resolved and persisted")
checkSourceAndDocs in ThisBuild := {
  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  val allConfigs = allBloopConfigFiles.value
  val fooBloopFile = readConfigFor("foo", allConfigs)

  val modules = fooBloopFile.project.resolution.get.modules
  assert(modules.nonEmpty, "Modules are empty!")
  val modulesEmpty = modules.map(m => m -> m.artifacts.nonEmpty)
  assert(
    modulesEmpty.forall(_._2),
    s"Modules ${modulesEmpty.filter(!_._2).map(_._1).mkString(", ")} have empty artifacts!"
  )
  val modulesWithSourceAndDocs = modules.map { m =>
    m -> {
      (m.artifacts.exists(_.classifier.contains("javadoc")) &&
      m.artifacts.exists(_.classifier.contains("sources")))
    }
  }
  assert(
    modulesWithSourceAndDocs.forall(_._2),
    s"Modules ${modulesWithSourceAndDocs.filter(!_._2).map(_._1).mkString(", ")} have no sources and docs!"
  )
  /*
  val classpathSize = fooBloopFile.project.classpath.size
  assert(classpathSize == modules.size, "There are more modules than classpath entries!")
 */
}
