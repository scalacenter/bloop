package bloop.integrations.config

import java.nio.file.{Files, Paths}

import bloop.config.ConfigSchema.{JavaConfig, JvmConfig, ProjectConfig, ScalaConfig, TestConfig, TestOptionsConfig}
import bloop.config.ConfigDecoders.projectConfigDecoder
import CirceEncoders.projectConfigEncoder
import metaconfig.{Conf, Configured}
import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser
import org.junit.Test

class JsonSpec {

  def parseConfig(config: ProjectConfig): Unit = {
    val emptyConfigJson = projectConfigEncoder(config).spaces4
    val parsedEmptyConfig = projectConfigDecoder.read(Conf.parseString(emptyConfigJson))
    projectConfigDecoder.read(Conf.parseString(emptyConfigJson)) match {
      case Configured.Ok(parsed) => assert(config == parsed)
      case Configured.NotOk(error) => sys.error(s"Could not parse simple config: $error")
    }
  }

  @Test def testEmptyConfigJson(): Unit = {
    parseConfig(ProjectConfig.empty)
  }

  @Test def testSimpleConfigJson(): Unit = {
    val workingDirectory = Paths.get(System.getProperty("user.dir"))
    val sourceFile = Files.createTempFile("Foo", ".scala")
    sourceFile.toFile.deleteOnExit()
    val scalaLibraryJar = Files.createTempFile("scala-library", ".jar")
    scalaLibraryJar.toFile.deleteOnExit()
    val classesDir = Files.createTempFile("scala-library", ".jar")
    classesDir.toFile.deleteOnExit()

    val config = ProjectConfig(
      "dummy-project",
      workingDirectory,
      List(sourceFile),
      List("dummy-2"),
      List(scalaLibraryJar),
      classesDir,
      ScalaConfig("org.scala-lang", "scala-compiler", "2.12.4", List("-warn"), List()),
      JvmConfig(Some(workingDirectory), Nil),
      JavaConfig(List("-version")),
      TestConfig(List(), TestOptionsConfig(Nil, Nil))
    )

    parseConfig(config)
  }
}
