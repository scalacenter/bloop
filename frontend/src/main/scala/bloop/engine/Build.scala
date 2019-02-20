package bloop.engine

import bloop.data.{Origin, Project}
import bloop.engine.Dag.DagResult
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.util.CacheHashCode
import bloop.io.ByteHasher
import monix.eval.Task

final case class Build private (
    origin: AbsolutePath,
    projects: List[Project]
) extends CacheHashCode {
  private val stringToProjects: Map[String, Project] = projects.map(p => p.name -> p).toMap
  private[bloop] val DagResult(dags, missingDeps, traces) = Dag.fromMap(stringToProjects)

  def getProjectFor(name: String): Option[Project] = stringToProjects.get(name)

  def getDagFor(project: Project): Dag[Project] =
    Dag.dagFor(dags, project).getOrElse(sys.error(s"Project $project does not have a DAG!"))

  def hasMissingDependencies(project: Project): Option[List[String]] = missingDeps.get(project)

  /**
   * Detect changes in the build definition since the last time it was loaded.
   *
   * @param logger A logger that receives errors, if any.
   * @return The status of the directory from which the build was loaded.
   */
  def checkForChange(logger: Logger): Task[Build.ReloadAction] = {
    val files = projects.iterator.map(p => p.origin.toAttributedPath).toSet
    val newFiles = BuildLoader.readConfigurationFilesInBase(origin, logger).toSet

    // This is the fast path to short circuit quickly if they are the same
    if (newFiles == files) Task.now(Build.ReturnPreviousState)
    else {
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
            case Some(p) if p.origin.hash == configuration.origin.hash => Nil
            case _ => List(configuration)
          }
        }
      }

      // Recompute all the build -- this step could be incremental but its cost is negligible
      Task.gatherUnordered(newOrModifiedConfigurations).map(_.flatten).map { newOrModified =>
        val newToAttributed = newFiles.iterator.map(ap => ap.path -> ap).toMap
        val deleted = files.toList.collect { case f if !newToAttributed.contains(f.path) => f.path }
        (newOrModified, deleted) match {
          case (Nil, Nil) => Build.ReturnPreviousState
          case _ => Build.UpdateState(newOrModified, deleted)
        }
      }
    }
  }
}

object Build {
  sealed trait ReloadAction
  case object ReturnPreviousState extends ReloadAction
  case class UpdateState(
      createdOrModified: List[ReadConfiguration],
      deleted: List[AbsolutePath]
  ) extends ReloadAction

  /** A configuration file is a combination of an absolute path and a file time. */
  case class ReadConfiguration(origin: Origin, bytes: Array[Byte]) extends CacheHashCode
}
