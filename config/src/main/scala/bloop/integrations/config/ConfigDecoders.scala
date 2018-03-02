package bloop.integrations.config

import java.nio.file.{Files, Path, Paths}

import metaconfig.{ConfDecoder, ConfError, Configured, generic}
import metaconfig.generic.Surface

import scala.util.{Failure, Success, Try}

import bloop.integrations.config.ConfigSchema.{
  JavaConfig,
  JvmConfig,
  ProjectConfig,
  ScalaConfig,
  TestArgumentConfig,
  TestConfig,
  TestFrameworkConfig,
  TestOptionsConfig
}

object ConfigDecoders {
  implicit val pathDecoder: ConfDecoder[Path] = ConfDecoder.stringConfDecoder.flatMap { str =>
    Try(Paths.get(str)) match {
      case Success(path) if Files.exists(path) => Configured.Ok(path)
      case Success(path) => ConfError.fileDoesNotExist(path).notOk
      case Failure(t) => Configured.error(s"Invalid path '$str' failed with '${t.getMessage}'")
    }
  }

  implicit val javaConfigSurface: Surface[JavaConfig] =
    generic.deriveSurface[JavaConfig]
  implicit val javaConfigDecoder: ConfDecoder[JavaConfig] =
    generic.deriveDecoder[JavaConfig](JavaConfig.empty)

  implicit val jvmConfigSurface: Surface[JvmConfig] =
    generic.deriveSurface[JvmConfig]
  implicit val jvmConfigDecoder: ConfDecoder[JvmConfig] =
    generic.deriveDecoder[JvmConfig](JvmConfig.empty)

  implicit val testFrameworkConfigSurface: Surface[TestFrameworkConfig] =
    generic.deriveSurface[TestFrameworkConfig]
  implicit val testFrameworkConfigDecoder: ConfDecoder[TestFrameworkConfig] =
    generic.deriveDecoder[TestFrameworkConfig](TestFrameworkConfig(Nil))

  implicit val testArgumentConfigSurface: Surface[TestArgumentConfig] =
    generic.deriveSurface[TestArgumentConfig]
  implicit val testArgumentConfigDecoder: ConfDecoder[TestArgumentConfig] =
    generic.deriveDecoder[TestArgumentConfig](TestArgumentConfig(Nil, None))

  implicit val testOptionsConfigSurface: Surface[TestOptionsConfig] =
    generic.deriveSurface[TestOptionsConfig]
  implicit val testOptionsConfigDecoder: ConfDecoder[TestOptionsConfig] =
    generic.deriveDecoder[TestOptionsConfig](TestOptionsConfig(Nil, Nil))

  implicit val testConfigSurface: Surface[TestConfig] =
    generic.deriveSurface[TestConfig]
  implicit val testConfigDecoder: ConfDecoder[TestConfig] =
    generic.deriveDecoder[TestConfig](TestConfig.empty)

  implicit val scalaConfigSurface: Surface[ScalaConfig] =
    generic.deriveSurface[ScalaConfig]
  implicit val scalaConfigDecoder: ConfDecoder[ScalaConfig] =
    generic.deriveDecoder[ScalaConfig](ScalaConfig.empty)

  implicit val projectConfigSurface: Surface[ProjectConfig] =
    generic.deriveSurface[ProjectConfig]
  implicit val projectConfigDecoder: ConfDecoder[ProjectConfig] =
    generic.deriveDecoder[ProjectConfig](ProjectConfig.empty)
}
