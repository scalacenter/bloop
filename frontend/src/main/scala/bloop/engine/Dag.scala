package bloop.engine

import bloop.Project
import scalaz.Show

sealed trait Dag[T]
final case class Leaf[T](value: T) extends Dag[T]
final case class Parent[T](value: T, children: List[Dag[T]]) extends Dag[T]

object Dag {
  class RecursiveCycle(path: List[Project])
      extends Exception(s"Not a DAG, cycle detected in ${path.map(_.name).mkString(" -> ")} ")

  def fromMap(projectsMap: Map[String, Project]): List[Dag[Project]] = {
    val visited = new scala.collection.mutable.HashMap[Project, Dag[Project]]()
    val visiting = new scala.collection.mutable.LinkedHashSet[Project]()
    val dependents = new scala.collection.mutable.HashSet[Dag[Project]]()
    val projects = projectsMap.values.toList
    def loop(project: Project, dependent: Boolean): Dag[Project] = {
      def markVisited(dag: Dag[Project]): Dag[Project] = { visited.+=(project -> dag); dag }
      def register(dag: Dag[Project]): Dag[Project] = {
        if (dependent && !dependents.contains(dag)) dependents.+=(dag); dag
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
    dags.filterNot(node => dependents.contains(node))
  }

  def dagFor[T](dags: List[Dag[T]], target: T): Option[Dag[T]] =
    dagFor(dags, Set(target)).flatMap(_.headOption)

  /**
   * Return a list of dags that match all the targets.
   *
   * The matched dags are returned in no particular order.
   *
   * @param dags The list of all dags.
   * @param targets The targets for which we want to find a dag.
   * @return An optional value of a list of dags.
   */
  def dagFor[T](dags: List[Dag[T]], targets: Set[T]): Option[List[Dag[T]]] = {
    dags.foldLeft[Option[List[Dag[T]]]](None) {
      case (found: Some[List[Dag[T]]], _) => found
      case (acc, dag) =>
        def aggregate(dag: Dag[T]): Option[List[Dag[T]]] = acc match {
          case Some(dags) => Some(dag :: dags)
          case None => Some(List(dag))
        }

        dag match {
          case Leaf(value) if targets.contains(value) => aggregate(dag)
          case Leaf(value) => None
          case Parent(value, children) if targets.contains(value) => aggregate(dag)
          case Parent(value, children) => dagFor(children, targets)
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

  def toDotGraph[T](dag: Dag[T])(implicit Show: Show[T]): String = {
    // Inefficient implementation, but there is no need for efficiency here.
    val all = Dag.dfs(dag).toSet
    val nodes = all.map { node =>
      val id = Show.shows(node)
      s""""$id" [label="$id"];"""
    }

    val nodeDags = Dag.dagFor(List(dag), all).head
    val edges = nodeDags.flatMap { dag =>
      val target = dag match {
        case Leaf(value) => Show.shows(value)
        case Parent(value, _) => Show.shows(value)
      }
      Dag.dfs(dag).map(Show.shows(_)).map(dep => s""""$dep" -> "$target";""")
    }

    s"""digraph "generated-graph" {
       | graph [ranksep=0, rankdir=LR];
       |${nodes.mkString("  ", "\n  ", "\n  ")}
       |${edges.mkString("  ", "\n  ", "")}
       |}""".stripMargin
  }

  def toDotGraph(dags: List[Dag[Project]]): String = {
    val projects = dags.flatMap(dfs).toSet
    val nodes = projects.map(node => s""""${node.name}" [label="${node.name}"];""")
    val edges = projects.flatMap(n => n.dependencies.map(p => s""""${n.name}" -> "$p";"""))
    s"""digraph "project" {
       | graph [ranksep=0, rankdir=LR];
       |${nodes.mkString("  ", "\n  ", "\n  ")}
       |${edges.mkString("  ", "\n  ", "")}
       |}""".stripMargin
  }
}
