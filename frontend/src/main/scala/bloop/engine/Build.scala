package bloop.engine

import bloop.Project
import bloop.io.{AbsolutePath, DirChecksum}

final case class Build(origin: AbsolutePath, projects: List[Project]) {

  private val originChecksum = DirChecksum(origin, Project.loadPattern)
  private val stringToProjects: Map[String, Project] = projects.map(p => p.name -> p).toMap
  private[bloop] val dags: List[Dag[Project]] = Dag.fromMap(stringToProjects)

  def getProjectFor(name: String): Option[Project] = stringToProjects.get(name)
  def getDagFor(project: Project): Dag[Project] =
    Dag.dagFor(dags, project).getOrElse(sys.error(s"Project $project does not have a DAG!"))

  /** Has this build definition changed since it was loaded? */
  def changed(): Boolean = originChecksum.changed()

}
