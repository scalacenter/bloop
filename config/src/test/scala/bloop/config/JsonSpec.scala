package bloop.config

import bloop.config.Config.File
import org.junit.{Assert, Test}

class JsonSpec {
  def parseConfig(jsonConfig: String): Config.File = {
    import io.circe.parser
    import ConfigEncoderDecoders._
    val parsed = parser.parse(jsonConfig).getOrElse(sys.error("error parsing"))
    allDecoder.decodeJson(parsed) match {
      case Right(parsedConfig) => parsedConfig
      case Left(failure) => throw failure
    }
  }

  def parseFile(config: File): Unit = {
    val jsonConfig = toStr(config)
    val parsedConfig = parseConfig(jsonConfig)
    Assert.assertEquals(parsedConfig, config)
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
