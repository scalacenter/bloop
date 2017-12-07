package bloop.engine

import bloop.Project

sealed trait Dag[T]
final case class Leaf[T](value: T) extends Dag[T]
final case class Parent[T](value: T, children: List[Dag[T]]) extends Dag[T]

object Dag {
  private[bloop] class RecursiveCycle(path: List[Project])
      extends Exception(s"Not a DAG, cycle detected in ${path.map(_.name).mkString(" -> ")} ")

  def fromMap(projectsMap: Map[String, Project]): List[Dag[Project]] = {
    val visited = new scala.collection.mutable.HashMap[Project, Dag[Project]]()
    val visiting = new scala.collection.mutable.LinkedHashSet[Project]()
    val dependees = new scala.collection.mutable.HashSet[Dag[Project]]()
    val projects = projectsMap.values.toList
    def loop(project: Project, dependee: Boolean): Dag[Project] = {
      def markVisited(dag: Dag[Project]): Dag[Project] = { visited.+=(project -> dag); dag }
      def register(dag: Dag[Project]): Dag[Project] = {
        if (dependee && !dependees.contains(dag)) dependees.+=(dag); dag
      }

      register {
        // Check that there are no cycles
        if (visiting.contains(project))
          throw new RecursiveCycle(visiting.toList :+ project)
        else visiting.+=(project)

        val dag = visited.get(project).getOrElse {
          val dependencies = project.dependencies.toList
          markVisited {
            project match {
              case leaf if dependencies.isEmpty => Leaf(leaf)
              case parent =>
                val childrenNodes = dependencies.iterator.map(name => projectsMap(name))
                Parent(parent, childrenNodes.map(loop(_, true)).toList)
            }
          }
        }

        visiting.-=(project)
        dag
      }
    }

    // Traverse through all the projects and only get the root nodes
    val dags = projects.map(loop(_, false))
    dags.filterNot(node => dependees.contains(node))
  }

  def dagFor[T](dags: List[Dag[T]], target: T): Option[Dag[T]] = {
    dags.foldLeft[Option[Dag[T]]](None) {
      case (found: Some[Dag[T]], _) => found
      case (acc, dag) =>
        dag match {
          case Leaf(value) if value == target => Some(dag)
          case Leaf(value) => None
          case Parent(value, children) if value == target => Some(dag)
          case Parent(value, children) => dagFor(children, target)
        }
    }
  }

  def dfs[T](dag: Dag[T]): List[T] = {
    def loop(dag: Dag[T], acc: List[T]): List[T] = {
      dag match {
        case Leaf(value) => value :: acc
        case Parent(value, children) =>
          children.foldLeft(value :: acc) { (acc, child) =>
            loop(child, acc)
          }
      }
    }

    // Inefficient, but really who cares?
    loop(dag, Nil).distinct.reverse
  }

  def toDotGraph(dags: List[Dag[Project]]): String = {
    val projects = dags.flatMap(dfs)
    val nodes = projects.map(node => s"""${node.name} [label="${node.name}"];""")
    val edges = projects.flatMap(n => n.dependencies.map(p => s"${n.name} -> $p;"))
    s"""digraph "project" {
       | graph [ranksep=0, rankdir=LR];
       |${nodes.mkString("  ", "\n  ", "\n  ")}
       |${edges.mkString("  ", "\n  ", "")}
       |}""".stripMargin
  }
}
