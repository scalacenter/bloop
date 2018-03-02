package bloop.integrations.config

import bloop.integrations.config.ConfigSchema.ProjectConfig

import ConfigDecoders.projectConfigDecoder
import CirceEncoders.projectConfigEncoder
import metaconfig.Conf
import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser

import org.junit.Test

class JsonSpec {
  @Test def testEmptyConfigJson(): Unit = {
    val emptyConfig = ProjectConfig.empty
    val emptyConfigJson = projectConfigEncoder(emptyConfig).spaces4
    val parsedEmptyConfig = projectConfigDecoder.read(Conf.parseString(emptyConfigJson))
    assert(emptyConfig == parsedEmptyConfig.getOrElse(sys.error("Could not parse empty config.")))
  }
}
