package bloop.bloopgun.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.{core => jsoniter}
import snailgun.logging.Logger

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
          val fallbackToEnvHome =
            settings.copy(javaHome = settings.javaHome.orElse(javaHomeEnv))
          Right(fallbackToEnvHome)
        case Failure(e: JsonReaderException) =>
          Left(e.getMessage())
        case Failure(e) =>
          throw new Exception(e)
      }
    }
  }

  def default: GlobalSettings =
    GlobalSettings(javaHome = javaHomeEnv)

  private def javaHomeEnv: Option[String] =
    Option(System.getenv().get("JAVA_HOME"))
}
