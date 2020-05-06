package bloop.data

import bloop.logging.Logger
import bloop.logging.DebugFilter
import bloop.io.AbsolutePath
import bloop.io.RelativePath
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.nio.file.Files
import bloop.tracing.TraceProperties
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, WriterConfig}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/**
 * Defines the settings of a given workspace. A workspace is a URI that has N
 * configuration files associated with it. Typically the workspace is the root
 * directory where all of the projects in the configuration files are defined.
 *
 * Workspace settings have a special status in bloop as they change the build
 * load semantics. These changes are represented with [[DetectedChange]]s
 * handled in the build loader.
 *
 * Both the user and bloop can write workspace settings to this file so bloop
 * needs to handle that scenario carefully.
 *
 * Workspace settings can be written to disk when, for example, Metals asks to
 * import a build and Bloop needs to cache the fact that a build needs to
 * enable Metals specific settings based on some inputs from the BSP clients.
 * These keys are usually the fields of the workspace settings.
 *
 * Another example is when the user manually goes to the file and changes a
 * setting in it.
 *
 * @param semanticDBVersion is the version that should be used to enable the
 * Semanticdb compiler plugin in a project.
 * @param semanticDBScalaVersions is the sequence of Scala versions for which
 * the SemanticDB plugin can be resolved for. Important to know for which
 * projects we should skip the resolution of the plugin.
 * @param refreshProjectsCommand is the command that should be run in the BSP
 * server before loading the state and presentings projects to the client.
 * @param traceSettings are the settings provided by the user that customize how
 * the bloop server should behave.
 */
case class WorkspaceSettings(
    // Managed by bloop or build tool
    semanticDBVersion: Option[String],
    supportedScalaVersions: Option[List[String]],
    // Managed by the user
    refreshProjectsCommand: Option[List[String]],
    traceSettings: Option[TraceSettings]
) {
  def withSemanticdbSettings: Option[(WorkspaceSettings, SemanticdbSettings)] =
    (semanticDBVersion, supportedScalaVersions) match {
      case (Some(semanticDBVersion), Some(supportedScalaVersions)) =>
        Some(this -> SemanticdbSettings(semanticDBVersion, supportedScalaVersions))
      case _ => None
    }

}

object WorkspaceSettings {
  def tracePropertiesFrom(settings: Option[WorkspaceSettings]): TraceProperties = {
    settings
      .flatMap(_.traceSettings)
      .map(TraceSettings.toProperties(_))
      .getOrElse(TraceProperties.default)
  }

  def fromSemanticdbSettings(
      semanticDBVersion: String,
      supportedScalaVersions: List[String]
  ): WorkspaceSettings = {
    WorkspaceSettings(Some(semanticDBVersion), Some(supportedScalaVersions), None, None)
  }

  /** Represents the supported changes in the workspace. */
  sealed trait DetectedChange
  final case object SemanticDBVersionChange extends DetectedChange

  /** File name to store Metals specific settings*/
  private[bloop] val settingsFileName = RelativePath("bloop.settings.json")

  private implicit val codecSettings: JsonValueCodec[WorkspaceSettings] =
    JsonCodecMaker.make[WorkspaceSettings](
      CodecMakerConfig.withTransientEmpty(false).withRequireCollectionFields(true)
    )

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
