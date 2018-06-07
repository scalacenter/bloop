package bloop.config

import java.nio.file.{Files, Paths}

import bloop.config.Config.{
  ClasspathOptions,
  CompileOptions,
  File,
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
import org.junit.Assert

class JsonSpec {
  def parseConfig(config: File): Unit = {
    val jsonConfig = allConfigEncoder(config).spaces4
    val parsedEmptyConfig = allConfigDecoder.read(Conf.parseString(jsonConfig))
    allConfigDecoder.read(Conf.parseString(jsonConfig)) match {
      case Configured.Ok(parsed) =>
        // Compare stringified representation because `Array` equals uses reference equality
        Assert.assertEquals(allConfigEncoder(parsed).spaces4, jsonConfig)
      case Configured.NotOk(error) => sys.error(s"Could not parse simple config: $error")
    }
  }

  @Test def testEmptyConfigJson(): Unit = {
    parseConfig(File.empty)
  }

  @Test def testSimpleConfigJson(): Unit = {
    parseConfig(File.dummyForTests)
  }
}
