package bloop.bloopgun.util

import com.github.plokhotnyuk.jsoniter_scala.{core => jsoniter}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import java.nio.file.Path
import java.nio.file.Paths
import snailgun.logging.Logger
import scala.util.Failure
import scala.util.Success
import java.nio.file.Files
import scala.util.Try

case class GlobalSettings(
    javaHome: Option[String] = None,
    javaOptions: Option[List[String]] = None
) {
  def javaBinary: String = {
    javaHome match {
      case Some(home) => Paths.get(home).resolve("bin").resolve("java").toString()
      case None => "java"
    }
  }
}

object GlobalSettings {
  private implicit val codecSettings: JsonValueCodec[GlobalSettings] =
    JsonCodecMaker.makeWithRequiredCollectionFields[GlobalSettings]

  def readFromFile(settingsPath: Path, logger: Logger): Either[String, GlobalSettings] = {
    if (!Files.isReadable(settingsPath))
      Left(s"Global settings file '$settingsPath' is not readable")
    else {
      logger.debug(s"Loading global settings from $settingsPath")
      val bytes = Files.readAllBytes(settingsPath)
      Try(jsoniter.readFromArray(bytes)) match {
        case Success(settings) =>
          Right(settings)
        case Failure(e: JsonReaderException) =>
          Left(e.getMessage())
        case Failure(e) =>
          throw e
      }
    }
  }

  def default: GlobalSettings = GlobalSettings()
}
