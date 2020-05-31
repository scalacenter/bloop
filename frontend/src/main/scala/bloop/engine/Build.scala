package bloop.engine

import bloop.data.{LoadedProject, Origin, Project, WorkspaceSettings}
import bloop.engine.Dag.DagResult
import bloop.io.Paths.AttributedPath
import bloop.io.{AbsolutePath, ByteHasher}
import bloop.logging.Logger
import bloop.util.CacheHashCode
import monix.eval.Task

import scala.annotation.tailrec
import scala.collection.mutable

final case class Build private (
    origin: AbsolutePath,
    loadedProjects: List[LoadedProject],
    workspaceSettings: Option[WorkspaceSettings]
) extends CacheHashCode {

  private val stringToProjects: Map[String, Project] =
    loadedProjects.map(lp => lp.project.name -> lp.project).toMap
  private[bloop] val DagResult(dags, missingDeps, traces) = Dag.fromMap(stringToProjects)

  def getProjectFor(name: String): Option[Project] =
    stringToProjects.get(name)

  def getDagFor(project: Project): Dag[Project] =
    Dag.dagFor(dags, project).getOrElse(sys.error(s"Project $project does not have a DAG!"))

  def hasMissingDependencies(project: Project): Option[List[String]] =
    missingDeps.get(project)

  def mainOnlyProjects: List[LoadedProject] =
    loadedProjects.filter(_.project.sbt.isEmpty)

  /**
   * Detect changes in the build definition since the last time it was loaded
   * and tell the compiler which action should be applied to update the build.
   *
   * The logic to incrementally update the build is complex due to the need of
   * transforming projects in-memory after they have been loaded. These
   * transformations depend on values of the workspace settings and
   * `checkForChange` defines many of the semantics of these settings and what
   * should be the implications that a change has in the whole build.
   *
   * @param newSettings The new settings that should be applied to detect changes.
   *                    These settings are passed by certain clients such as Metals
   *                    to apply in-memory transformations on projects. They can
   *                    differ from the settings written to disk.
   * @param logger A logger that receives errors, if any.
   * @return The status of the directory from which the build was loaded.
   */
  def checkForChange(
      newSettings: Option[WorkspaceSettings],
      logger: Logger
  ): Task[Build.ReloadAction] = {
    val oldFilesMap = loadedProjects.iterator.map { lp =>
      val origin = lp.project.origin
      origin.path -> origin.toAttributedPath
    }.toMap

    @tailrec
    def sbtFilesRecursively(p: AbsolutePath, files: List[AttributedPath]): List[AttributedPath] = {
      val projectDir = p.resolve("project")
      val sbtDir = projectDir.resolve(".bloop")
      if (sbtDir.exists && sbtDir.isDirectory) {
        val newFiles = BuildLoader.readConfigurationFilesInBase(sbtDir, logger)
        sbtFilesRecursively(projectDir, newFiles ++ files)
      } else files
    }

    val newFiles = {
      val main = BuildLoader.readConfigurationFilesInBase(origin, logger).toSet
      val sbt = sbtFilesRecursively(origin.getParent, List.empty).toSet
      main ++ sbt
    }

    val newToAttributed = newFiles.iterator.map(ap => ap.path -> ap).toMap

    val currentSettings = WorkspaceSettings.readFromFile(origin, logger)
    val settingsForReload = pickSettingsForReload(currentSettings, newSettings, logger)
    val changedSettings = currentSettings != settingsForReload
    val filesToProjects = loadedProjects.iterator.map(lp => lp.project.origin.path -> lp).toMap

    val detectedChanges = newFiles.map { f =>
      def hasSameMetadata: Boolean = {
        oldFilesMap.get(f.path) match {
          case Some(oldFile) if oldFile == f => true
          case _ => false
        }
      }

      def readConfiguration = {
        val bytes = f.path.readAllBytes
        val hash = ByteHasher.hashBytes(bytes)
        Build.ReadConfiguration(Origin(f, hash), bytes)
      }

      def invalidateProject(
          project: Project,
          externalChanges: Option[List[WorkspaceSettings.DetectedChange]]
      ) = {
        val changes = externalChanges.getOrElse {
          // Add all changes here so that projects with no changes get fully invalidated
          List(WorkspaceSettings.SemanticDBVersionChange)
        }
        List(Build.InvalidatedInMemoryProject(project, changes))
      }

      Task {
        filesToProjects.get(f.path) match {
          case None => List(Build.NewProject(readConfiguration))

          case Some(LoadedProject.RawProject(project)) =>
            def invalidateIfSettings = {
              // Attempt to configure project when settings exist
              if (newSettings.isEmpty || !changedSettings) Nil
              else invalidateProject(project, None)
            }

            if (hasSameMetadata) invalidateIfSettings
            else {
              val configuration = readConfiguration
              val hasSameHash = project.origin.hash == configuration.origin.hash
              if (!hasSameHash) List(Build.ModifiedProject(configuration))
              else invalidateIfSettings
            }

          case Some(LoadedProject.ConfiguredProject(project, original, settings)) =>
            findUpdateSettingsAction(Some(settings), settingsForReload) match {
              case Build.AvoidReload(_) =>
                val options = project.scalacOptions
                val reattemptConfiguration = newSettings.nonEmpty && {
                  !Project.hasSemanticDBEnabledInCompilerOptions(project.scalacOptions)
                }

                if (reattemptConfiguration) {
                  invalidateProject(project, None)
                } else if (hasSameMetadata) {
                  Nil
                } else {
                  val configuration = readConfiguration
                  val hasSameHash = original.origin.hash == configuration.origin.hash
                  if (hasSameHash) Nil
                  else List(Build.ModifiedProject(configuration))
                }

              case f: Build.ForceReload =>
                if (hasSameMetadata) {
                  invalidateProject(original, Some(f.changes))
                } else {
                  val configuration = readConfiguration
                  val hasSameHash = original.origin.hash == configuration.origin.hash
                  if (hasSameHash) invalidateProject(original, Some(f.changes))
                  else List(Build.ModifiedProject(configuration))
                }
            }
        }
      }
    }

    Task.gatherUnordered(detectedChanges).map(_.flatten).map { changes =>
      val deleted = oldFilesMap.values.collect {
        case f if !newToAttributed.contains(f.path) => f.path
      }

      (changes, deleted) match {
        case (Nil, Nil) => Build.ReturnPreviousState
        case (changes, deleted) =>
          val added = new mutable.ListBuffer[Build.ReadConfiguration]()
          val modified = new mutable.ListBuffer[Build.ReadConfiguration]()
          val inMemoryChanged = new mutable.ListBuffer[Build.InvalidatedInMemoryProject]()
          changes.foreach {
            case Build.NewProject(project) => added.+=(project)
            case Build.ModifiedProject(project) => modified.+=(project)
            case change: Build.InvalidatedInMemoryProject => inMemoryChanged.+=(change)
          }

          Build.UpdateState(
            added.toList,
            modified.toList,
            deleted.toList,
            inMemoryChanged.toList,
            settingsForReload,
            settingsForReload.nonEmpty && changedSettings
          )
      }
    }
  }

  def pickSettingsForReload(
      currentSettings: Option[WorkspaceSettings],
      newSettings: Option[WorkspaceSettings],
      logger: Logger
  ): Option[WorkspaceSettings] = {
    findUpdateSettingsAction(currentSettings, newSettings) match {
      case Build.AvoidReload(settings) => settings
      case Build.ForceReload(settings, _) => Some(settings)
    }
  }

  /**
   * Produces the action to update the build based on changes in the settings.
   *
   * The order in which settings are compared matters because if current and
   * new settings exist and don't have any conflict regarding the semantics
   * of the build process, the new settings are returned so that they are
   * mapped with the projects that have changed and have been reloaded.
   */
  def findUpdateSettingsAction(
      currentSettings: Option[WorkspaceSettings],
      newSettings: Option[WorkspaceSettings]
  ): Build.UpdateSettingsAction = {
    (currentSettings, newSettings) match {
      case (Some(currentSettings), Some(newSettings))
          if currentSettings.semanticDBVersion != newSettings.semanticDBVersion =>
        Build.ForceReload(newSettings, List(WorkspaceSettings.SemanticDBVersionChange))
      case (Some(_), Some(newSettings)) => Build.AvoidReload(Some(newSettings))
      case (None, Some(newSettings)) =>
        Build.ForceReload(newSettings, List(WorkspaceSettings.SemanticDBVersionChange))
      case (Some(currentSettings), None) => Build.AvoidReload(Some(currentSettings))
      case (None, None) => Build.AvoidReload(None)
    }
  }
}

object Build {
  sealed trait ReloadAction
  final case object ReturnPreviousState extends ReloadAction
  case class UpdateState(
      created: List[ReadConfiguration],
      modified: List[ReadConfiguration],
      deleted: List[AbsolutePath],
      invalidated: List[InvalidatedInMemoryProject],
      settingsForReload: Option[WorkspaceSettings],
      writeSettingsToDisk: Boolean
  ) extends ReloadAction {
    def createdOrModified = created ++ modified
  }

  sealed trait UpdateSettingsAction
  final case class AvoidReload(settings: Option[WorkspaceSettings]) extends UpdateSettingsAction
  final case class ForceReload(
      settings: WorkspaceSettings,
      changes: List[WorkspaceSettings.DetectedChange]
  ) extends UpdateSettingsAction

  sealed trait ProjectChange
  final case class NewProject(configuration: Build.ReadConfiguration) extends ProjectChange
  final case class ModifiedProject(configuration: Build.ReadConfiguration) extends ProjectChange
  final case class InvalidatedInMemoryProject(
      project: Project,
      changes: List[WorkspaceSettings.DetectedChange]
  ) extends ProjectChange

  /** A configuration file is a combination of an absolute path and a file time. */
  case class ReadConfiguration(origin: Origin, bytes: Array[Byte]) extends CacheHashCode
}
