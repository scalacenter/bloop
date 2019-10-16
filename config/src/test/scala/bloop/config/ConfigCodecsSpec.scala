package bloop.config

import bloop.config.Config.File
import org.junit.{Assert, Test}
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.io.BufferedReader
import java.io.InputStreamReader
import scala.io.Source

class ConfigCodecsSpec {
  import ConfigCodecs._
  def parseConfig(contents: String): Config.File =
    bloop.config.read(contents.getBytes(StandardCharsets.UTF_8)).right.get
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
    parseFile(File.dummyForTests)
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
    val contents = Source.fromInputStream(
      this.getClass.getClassLoader.getResourceAsStream("real-world-config.json")
    )
    parseConfig(contents.mkString)
    ()
  }
}
