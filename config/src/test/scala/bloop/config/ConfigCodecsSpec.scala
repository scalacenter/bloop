package bloop.config

import bloop.config.Config.File
import org.junit.{AfterClass, Assert, Test}
import java.nio.charset.StandardCharsets

object ConfigCodecsSpec {
  val dummyFile = File.dummyForTests

  @AfterClass def afterClass(): Unit = {
    val filesToDelete = dummyFile.project.classpath ++ dummyFile.project.sources :+ dummyFile.project.classesDir :+ dummyFile.project.out
    filesToDelete.foreach(TargetPlatform.deleteTempFile)
  }
}

class ConfigCodecsSpec {
  import bloop.config.ConfigCodecs._
  def parseConfig(contents: String): Config.File = {
    bloop.config.read(contents.getBytes(StandardCharsets.UTF_8)) match {
      case Right(file) => file
      case Left(throwable) => throw throwable
    }
  }

  def parseFile(configFile: File): Unit = {
    val jsonConfig = bloop.config.write(configFile)
    val parsedConfig = parseConfig(jsonConfig)
    Assert.assertEquals(configFile, parsedConfig)
  }

  @Test def testEmptyConfigJson(): Unit = {
    val configFile = File.empty
    val jsonConfig = bloop.config.write(configFile)
    // Assert that empty collection fields such as sources are present in the format
    Assert.assertTrue(jsonConfig.contains("\"sources\""))
    val parsedConfig = parseConfig(jsonConfig)
    Assert.assertEquals(configFile, parsedConfig)
  }

  @Test def testSimpleConfigJson(): Unit = {
    parseFile(ConfigCodecsSpec.dummyFile)
  }

  @Test def testIdea(): Unit = {
    val jsonConfig =
      """
        |{
        |    "version" : "1.0.0",
        |    "project" : {
        |        "name" : "",
        |        "directory" : "",
        |        "sources" : [
        |        ],
        |        "dependencies" : [
        |        ],
        |        "classpath" : [
        |        ],
        |        "out" : "",
        |        "classesDir" : "",
        |        "hello": 1,
        |        "iAmBinaryCompatible": [
        |          1, 2, 3, 4
        |        ]
        |    }
        |}
      """.stripMargin
    parseConfig(jsonConfig)
    ()
  }

  @Test def testRealWorldJsonFile(): Unit = {
    parseConfig(TestPlatform.getResourceAsString("real-world-config.json"))
    ()
  }
}
