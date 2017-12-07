package bloop.engine

import bloop.Project
import bloop.io.AbsolutePath

final case class Build(origin: AbsolutePath, projects: List[Project]) {
  private val stringToProjects: Map[String, Project] = projects.map(p => p.name -> p).toMap
  private val dags: List[Dag[Project]] = Dag.fromMap(stringToProjects)

  def getProjectFor(name: String): Option[Project] = stringToProjects.get(name)
  def getDagFor(project: Project): Dag[Project] =
    Dag.dagFor(dags, project).getOrElse(sys.error(s"Project $project does not have a DAG!"))
  def reachableFrom(from: Project): List[Project] = Dag.dfs(getDagFor(from))
}
