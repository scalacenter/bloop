package bloop.integrations.config

import java.nio.file.{Files, Paths}

import bloop.config.Config.{
  All,
  ClasspathOptions,
  Java,
  Jvm,
  Project,
  Scala,
  TestOptions,
  Test => ConfigTest
}

import bloop.config.ConfigDecoders.allConfigDecoder
import bloop.config.ConfigEncoders.allConfigEncoder
import metaconfig.{Conf, Configured}
import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser
import org.junit.Test

class JsonSpec {
  def parseConfig(config: All): Unit = {
    val jsonConfig = allConfigEncoder(config).spaces4
    println(jsonConfig)
    val parsedEmptyConfig = allConfigDecoder.read(Conf.parseString(jsonConfig))
    allConfigDecoder.read(Conf.parseString(jsonConfig)) match {
      case Configured.Ok(parsed) => assert(config == parsed)
      case Configured.NotOk(error) => sys.error(s"Could not parse simple config: $error")
    }
  }

  @Test def testEmptyConfigJson(): Unit = {
    parseConfig(All.empty)
  }

  @Test def testSimpleConfigJson(): Unit = {
    val workingDirectory = Paths.get(System.getProperty("user.dir"))
    val sourceFile = Files.createTempFile("Foo", ".scala")
    sourceFile.toFile.deleteOnExit()
    val scalaLibraryJar = Files.createTempFile("scala-library", ".jar")
    scalaLibraryJar.toFile.deleteOnExit()
    val classesDir = Files.createTempFile("scala-library", ".jar")
    classesDir.toFile.deleteOnExit()

    val project = Project(
      "dummy-project",
      workingDirectory,
      List(sourceFile),
      List("dummy-2"),
      List(scalaLibraryJar),
      ClasspathOptions.empty,
      classesDir,
      Scala("org.scala-lang", "scala-compiler", "2.12.4", List("-warn"), List()),
      Jvm(Some(Paths.get("/usr/lib/jvm/java-8-jdk")), Nil),
      Java(List("-version")),
      ConfigTest(List(), TestOptions(Nil, Nil))
    )

    parseConfig(All(All.LatestVersion, project))
  }
}
