package bloop.engine

import bloop.data.{Origin, Project}
import bloop.engine.Dag.DagResult
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.util.CacheHashCode
import bloop.io.ByteHasher
import monix.eval.Task
import bloop.data.WorkspaceSettings
import bloop.logging.DebugFilter

final case class Build private (
    origin: AbsolutePath,
    settings: Option[WorkspaceSettings],
    projects: List[Project]
) extends CacheHashCode {
  private val stringToProjects: Map[String, Project] = projects.map(p => p.name -> p).toMap
  private[bloop] val DagResult(dags, missingDeps, traces) = Dag.fromMap(stringToProjects)

  def getProjectFor(name: String): Option[Project] = stringToProjects.get(name)

  def getDagFor(project: Project): Dag[Project] =
    Dag.dagFor(dags, project).getOrElse(sys.error(s"Project $project does not have a DAG!"))

  def hasMissingDependencies(project: Project): Option[List[String]] = missingDeps.get(project)

  /**
   * Detect changes in the build definition since the last time it was loaded
   * and tell the compiler which action should be applied to update the build.
   *
   * There are two major kinds of changes:
   *
   * 1. A change in a configuration file backing up the metadata of a project.
   * 2. A change in a workspace setting that requires transforming a project
   *    after it has been loaded from the configuration file.
   *
   * @param newSettings The new settings that should be applied to detect changes.
   *                    These settings are passed by certain clients such as Metals
   *                    to apply in-memory transformations on projects. They can
   *                    differ from the settings written to disk.
   * @param logger A logger that receives errors, if any.
   * @param retryFailedProjects
   * @return The status of the directory from which the build was loaded.
   */
  def checkForChange(
      newSettings: Option[WorkspaceSettings],
      logger: Logger,
      retryFailedProjects: Boolean
  ): Task[Build.ReloadAction] = {
    val files = projects.iterator.map(p => p.origin.toAttributedPath).toSet
    val newFiles = BuildLoader.readConfigurationFilesInBase(origin, logger).toSet

    /*
    def settingsForProjectReload(workspaceSettings: WorkspaceSettings) =
      (workspaceSettings.semanticDBVersion, workspaceSettings.supportedScalaVersions)
    val changedSettings = reapplySettings || (
      (incomingSettings.nonEmpty &&
        settings.map(settingsForProjectReload) != incomingSettings.map(settingsForProjectReload))
    )

    if (reapplySettings) {
      logger.debug(s"Incoming BSP workspace settings require reloading all projects")(
        DebugFilter.All
      )
    }
     */

    // This is the fast path to short circuit quickly if they are the same
    if (newFiles == files && !changedSettings) {
      Task.now(Build.ReturnPreviousState)
    } else {
      val filesToAttributed = projects.iterator.map(p => p.origin.path -> p).toMap
      // There has been at least either one addition, one removal or one change in a file time
      val newOrModifiedConfigurations = newFiles.map { f =>
        Task {
          val configuration = {
            val bytes = f.path.readAllBytes
            val hash = ByteHasher.hashBytes(bytes)
            Build.ReadConfiguration(Origin(f, hash), bytes)
          }

          filesToAttributed.get(f.path) match {
            case _ if changedSettings => List(configuration)
            case Some(p) if p.origin.hash == configuration.origin.hash => Nil
            case _ => List(configuration)
          }
        }
      }

      // Recompute all the build -- this step could be incremental but its cost is negligible
      Task.gatherUnordered(newOrModifiedConfigurations).map(_.flatten).map { newOrModified =>
        val newToAttributed = newFiles.iterator.map(ap => ap.path -> ap).toMap
        val deleted = files.toList.collect { case f if !newToAttributed.contains(f.path) => f.path }
        (newOrModified, deleted, changedSettings) match {
          case (Nil, Nil, false) => Build.ReturnPreviousState
          case _ => Build.UpdateState(newOrModified, deleted, incomingSettings)
        }
      }
    }
  }

  sealed trait UpdateSettingsAction

  final case class ForceReload(
      settings: WorkspaceSettings,
      changes: List[WorkspaceSettings.DetectedChange]
  ) extends UpdateSettingsAction

  final case class WriteSettings(
      settings: WorkspaceSettings
  ) extends UpdateSettingsAction

  /**
   * Picks the settings that have to be used to reload the build.
   * @return Either the current settings when nothing has changed or the new
   * settingsb
   */
  def findUpdateSettingsAction(
      newSettings: Option[WorkspaceSettings],
      logger: Logger
  ): Either[Option[WorkspaceSettings], UpdateSettingsAction] = {
    val currentSettings = WorkspaceSettings.readFromFile(origin, logger)
    (currentSettings, newSettings) match {
      case (Some(currentSettings), Some(newSettings))
          if currentSettings.semanticDBVersion != newSettings.semanticDBVersion =>
        // Write the only supported change affecting build load semantics so far
        val changes = List(
          WorkspaceSettings.SemanticdbVersionChange(newSettings.semanticDBVersion)
        )
        Right(ForceReload(newSettings, changes))
      case (Some(_), Some(newSettings)) => Left(Some(newSettings))
      case (None, Some(newSettings)) => Right(WriteSettings(newSettings))
      case (Some(currentSettings), None) => Left(Some(currentSettings))
      case (None, None) => Left(None)
    }
  }
}

object Build {
  sealed trait ReloadAction
  case object ReturnPreviousState extends ReloadAction
  case class UpdateState(
      createdOrModified: List[ReadConfiguration],
      deleted: List[AbsolutePath],
      changedSetings: Option[WorkspaceSettings]
  ) extends ReloadAction

  /** A configuration file is a combination of an absolute path and a file time. */
  case class ReadConfiguration(origin: Origin, bytes: Array[Byte]) extends CacheHashCode
}
