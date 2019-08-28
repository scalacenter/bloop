package bloop.integrations.sbt

import java.nio.file.{Path, Files}
import java.nio.charset.StandardCharsets

object ConfigReader {
  def read(configDir: Path): Option[bloop.config.Config.File] = {
    import io.circe.parser
    import bloop.config.ConfigEncoderDecoders._
    val jsonConfig = new String(Files.readAllBytes(configDir), StandardCharsets.UTF_8)
    parser.parse(jsonConfig).right.toOption.flatMap { parsed =>
      allDecoder.decodeJson(parsed) match {
        case Right(parsedConfig) => Some(parsedConfig)
        case Left(failure) => None
      }
    }
  }
}
