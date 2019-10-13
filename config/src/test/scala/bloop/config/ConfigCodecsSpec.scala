package bloop.config

import bloop.config.Config.File
import com.github.plokhotnyuk.jsoniter_scala.core._
import org.junit.{Assert, Test}

class ConfigCodecsSpec {
  def parseConfig(jsonConfig: String): Config.File = {
    import ConfigCodecs._
    readFromString[Config.File](jsonConfig)
  }

  def parseFile(config: File): Unit = {
    import ConfigCodecs._
    val jsonConfig = writeToString(config)
    val parsedConfig = parseConfig(jsonConfig)
    Assert.assertEquals(config, parsedConfig)
  }

  @Test def testEmptyConfigJson(): Unit = {
    parseFile(File.empty)
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
}
