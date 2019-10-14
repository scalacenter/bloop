package bloop.util

import bloop.data.Project
import scala.collection.mutable

object TopologicalSort {

  def reachable(base: Project, projects: Map[String, Project]): Map[String, Project] = {
    val out = mutable.Map.empty[String, Project]

    def push(proj: Project): Unit = {
      out += proj.name -> proj
      proj.dependencies.foreach(dep => push(projects(dep)))
    }

    push(base)

    out.toMap
  }

  def sort(projects: Map[String, Project]): Array[Array[Project]] = {
    val (leaves, nodes) = projects.partition(_._2.dependencies.isEmpty)
    if (projects.isEmpty) Array.empty
    else if (leaves.isEmpty) throw new IllegalArgumentException("Not a DAG")
    else {
      val newNodes =
        nodes.mapValues(p => p.copy(dependencies = p.dependencies.filterNot(leaves.contains)))
      Array(leaves.values.toArray) ++ sort(newNodes)
    }
  }

  def tasks(from: Project, projects: Map[String, Project]): Array[Array[Project]] =
    sort(reachable(from, projects))
      .map(_.map(p => p.copy(dependencies = projects(p.name).dependencies)))

}
