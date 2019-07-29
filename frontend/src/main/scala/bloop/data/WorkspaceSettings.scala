package bloop.data

import bloop.engine.BuildLoader
import bloop.logging.Logger
import bloop.logging.DebugFilter
import bloop.config.ConfigEncoderDecoders
import bloop.io.AbsolutePath
import bloop.DependencyResolution
import bloop.io.RelativePath

import scala.util.Try
import scala.util.Failure
import scala.util.Success

import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets

import io.circe.Json
import io.circe.parser
import io.circe.Printer
import io.circe.Decoder
import io.circe.ObjectEncoder
import io.circe.JsonObject

case class WorkspaceSettings(
    semanticDBVersion: String,
    supportedScalaVersions: List[String]
)

object WorkspaceSettings {

  sealed trait DetectedChange
  final case class SemanticdbVersionChange(newVersion: String) extends DetectedChange

  /** File name to store Metals specific settings*/
  private[bloop] val settingsFileName = RelativePath("bloop.settings.json")

  import io.circe.derivation._
  private val settingsEncoder: ObjectEncoder[WorkspaceSettings] = deriveEncoder
  private val settingsDecoder: Decoder[WorkspaceSettings] = deriveDecoder

  def readFromFile(configPath: AbsolutePath, logger: Logger): Option[WorkspaceSettings] = {
    val settingsPath = configPath.resolve(settingsFileName)
    if (!settingsPath.isFile) None
    else {
      val bytes = Files.readAllBytes(settingsPath.underlying)
      logger.debug(s"Loading workspace settings from $settingsFileName")(DebugFilter.All)
      val contents = new String(bytes, StandardCharsets.UTF_8)
      parser.parse(contents) match {
        case Left(e) => throw e
        case Right(json) => Option(fromJson(json))
      }
    }
  }

  def writeToFile(configDir: AbsolutePath, settings: WorkspaceSettings): Either[Throwable, Path] = {
    Try {
      val jsonObject = settingsEncoder(settings)
      val output = Printer.spaces4.copy(dropNullValues = true).pretty(jsonObject)
      Files.write(
        configDir.resolve(settingsFileName).underlying,
        output.getBytes(StandardCharsets.UTF_8)
      )
    }.toEither
  }

  def fromJson(json: Json): WorkspaceSettings = {
    settingsDecoder.decodeJson(json) match {
      case Right(settings) => settings
      case Left(failure) => throw failure
    }
  }

  /**
   * Detects the workspace directory of a project.
   *
   * Bloop doesn't have the notion of workspace directory yet so this is just an
   * approximation. We assume that the parent of `.bloop` is the workspace. This
   * assumption is broken when source dependencies are used because we inline the
   * configuration files of the projects in source dependencies into a single
   * .bloop configuration directory. To fix this well-known limitation, we need
   * to introduce a new field to the bloop configuration file so that we can map
   * a project with a workspace irrevocably.
   */
  def detectWorkspaceDirectory(project: Project, settings: WorkspaceSettings): AbsolutePath = {
    val configFile = project.origin.path
    val configDir = configFile.getParent
    configDir.getParent
  }
}
