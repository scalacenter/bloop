package bloop.config

import java.net.URLClassLoader

import bloop.config.Config.File
import org.junit.{Assert, Test}

class JsonSpec {
  def parseConfig(config: File): Unit = {
    import io.circe.parser
    import ConfigEncoderDecoders._
    val jsonConfig = toStr(config)
    val parsed = parser.parse(jsonConfig).getOrElse(sys.error("error parsing"))
    allDecoder.decodeJson(parsed) match {
      case Right(parsedConfig) => Assert.assertEquals(parsedConfig, config)
      case Left(failure) => throw failure
    }
  }

  @Test def testEmptyConfigJson(): Unit = {
    parseConfig(File.empty)
  }

  @Test def testSimpleConfigJson(): Unit = {
    parseConfig(File.dummyForTests)
  }
}
