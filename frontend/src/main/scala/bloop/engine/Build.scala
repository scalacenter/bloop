package bloop.engine

import bloop.Project
import bloop.io.{AbsolutePath, FileTracker}
import bloop.logging.Logger

final case class Build private (origin: AbsolutePath,
                                projects: List[Project],
                                tracker: FileTracker) {

  private val stringToProjects: Map[String, Project] = projects.map(p => p.name -> p).toMap
  private[bloop] val dags: List[Dag[Project]] = Dag.fromMap(stringToProjects)

  def getProjectFor(name: String): Option[Project] = stringToProjects.get(name)
  def getDagFor(project: Project): Dag[Project] =
    Dag.dagFor(dags, project).getOrElse(sys.error(s"Project $project does not have a DAG!"))

  /**
   * Has this build definition changed since it was loaded?
   *
   * @param logger A logger that receives errors, if any.
   * @return The status of the directory from which the build was loaded.
   */
  def changed(logger: Logger): FileTracker.Status = tracker.changed(logger)

}

object Build {
  def apply(origin: AbsolutePath, projects: List[Project]): Build = {
    val checksum = FileTracker(origin, Project.loadPattern, maxDepth = Project.loadDepth)
    new Build(origin, projects, checksum)
  }
}
