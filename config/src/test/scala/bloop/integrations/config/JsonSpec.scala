package bloop.integrations.config

import java.nio.file.{Files, Paths}

import bloop.config.Config.{Java, Jvm, Project, Scala, Test, TestOptions}
import bloop.config.ConfigDecoders.projectConfigDecoder
import CirceEncoders.projectConfigEncoder
import metaconfig.{Conf, Configured}
import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser
import org.junit.Test

class JsonSpec {

  def parseConfig(config: Project): Unit = {
    val emptyConfigJson = projectConfigEncoder(config).spaces4
    val parsedEmptyConfig = projectConfigDecoder.read(Conf.parseString(emptyConfigJson))
    projectConfigDecoder.read(Conf.parseString(emptyConfigJson)) match {
      case Configured.Ok(parsed) => assert(config == parsed)
      case Configured.NotOk(error) => sys.error(s"Could not parse simple config: $error")
    }
  }

  @Test def testEmptyConfigJson(): Unit = {
    parseConfig(Project.empty)
  }

  @Test def testSimpleConfigJson(): Unit = {
    val workingDirectory = Paths.get(System.getProperty("user.dir"))
    val sourceFile = Files.createTempFile("Foo", ".scala")
    sourceFile.toFile.deleteOnExit()
    val scalaLibraryJar = Files.createTempFile("scala-library", ".jar")
    scalaLibraryJar.toFile.deleteOnExit()
    val classesDir = Files.createTempFile("scala-library", ".jar")
    classesDir.toFile.deleteOnExit()

    val config = Project(
      "dummy-project",
      workingDirectory,
      List(sourceFile),
      List("dummy-2"),
      List(scalaLibraryJar),
      classesDir,
      Scala("org.scala-lang", "scala-compiler", "2.12.4", List("-warn"), List()),
      Jvm(Some(workingDirectory), Nil),
      Java(List("-version")),
      Test(List(), TestOptions(Nil, Nil))
    )

    parseConfig(config)
  }
}
