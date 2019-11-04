package bloop.data

import bloop.engine.BuildLoader
import bloop.logging.Logger
import bloop.logging.DebugFilter
import bloop.io.AbsolutePath
import bloop.DependencyResolution
import bloop.io.RelativePath

import scala.util.Try
import scala.util.Failure
import scala.util.Success

import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, WriterConfig}
import com.github.plokhotnyuk.jsoniter_scala.macros.{JsonCodecMaker, CodecMakerConfig}

/**
 * Defines the settings of a given workspace. A workspace is a URI that has N
 * configuration files associated with it. Typically the workspace is the root
 * directory where all of the projects in the configuration files are defined.
 *
 * Workspace settings have a special status in bloop as they change the build
 * load semantics. Only bloop's build server has permission to write workspace
 * settings and the existence of the workspace settings file is an internal
 * detail.
 *
 * Workspace settings can be written to disk when, for example, Metals asks to
 * import a build and Bloop needs to cache the fact that a build needs to
 * enable Metals specific settings based on some inputs from the BSP clients.
 * These keys are usually the fields of the workspace settings.
 *
 * @param semanticDBVersion The version that should be used to enable the
 * Semanticdb compiler plugin in a project.
 * @param semanticDBScalaVersions The sequence of Scala versions for which the
 * SemanticDB plugin can be resolved for. Important to know for which projects
 * we should skip the resolution of the plugin.
 */
case class WorkspaceSettings(
    semanticDBVersion: String,
    supportedScalaVersions: List[String]
)

object WorkspaceSettings {

  /** Represents the supported changes in the workspace. */
  sealed trait DetectedChange
  final case object SemanticDBVersionChange extends DetectedChange

  /** File name to store Metals specific settings*/
  private[bloop] val settingsFileName = RelativePath("bloop.settings.json")

  private implicit val codecSettings: JsonValueCodec[WorkspaceSettings] =
    JsonCodecMaker.make[WorkspaceSettings](CodecMakerConfig.withTransientEmpty(false))
  import com.github.plokhotnyuk.jsoniter_scala.{core => jsoniter}
  def readFromFile(configPath: AbsolutePath, logger: Logger): Option[WorkspaceSettings] = {
    val settingsPath = configPath.resolve(settingsFileName)
    if (!settingsPath.isFile) None
    else {
      logger.debug(s"Loading workspace settings from $settingsFileName")(DebugFilter.All)
      val bytes = Files.readAllBytes(settingsPath.underlying)
      Try(jsoniter.readFromArray(bytes)) match {
        case Success(settings) => Option(settings)
        case Failure(e) => throw e
      }
    }
  }

  def writeToFile(
      configDir: AbsolutePath,
      settings: WorkspaceSettings,
      logger: Logger
  ): AbsolutePath = {
    val settingsFile = configDir.resolve(settingsFileName)
    logger.debug(s"Writing workspace settings to $settingsFile")(DebugFilter.All)
    val bytes = jsoniter.writeToArray(settings, WriterConfig.withIndentionStep(4))
    Files.write(settingsFile.underlying, bytes)
    settingsFile
  }
}
