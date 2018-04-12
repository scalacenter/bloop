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

  def transitive[T](dag: Dag[T]): List[Dag[T]] = {
    dag match {
      case leaf: Leaf[T] => List(leaf)
      case p @ Parent(value, children) => p :: children.flatMap(transitive)
    }
  }

  /**
    * Reduce takes a list of dags and a set of targets and outputs the smallest
    * set of disjoint DAGs that include all the targets (transitively).
    *
    * Therefore, all the nodes in `targets` that have a children relationship
    * with another node in `targets` will be subsumed by the latter and removed
    * from the returned set of DAGs nodes.
    *
    * This operation is necessary to remove the repetition of a transitive
    * action over the nodes in targets. This operation has not been tweaked
    * for performance yet because it's unlikely we stumble upon densely
    * populated DAGs (in the magnitude of hundreds) with tons of inter-dependencies
    * in real-world module graphs.
    *
    * To make this operation more efficient, we may want to do indexing of
    * transitives and then cache them in the build so that we don't have to
    * recompute them every time.

    *
    * @param dags The forest of disjoint DAGs from which we start from.
    * @param targets The targets to be deduplicated transitively.
    * @return The smallest set of disjoint DAGs including all targets.
    */
  def reduce[T](dags: List[Dag[T]], targets: Set[T]): Set[T] = {
    val transitives = scala.collection.mutable.HashMap[Dag[T], List[T]]()
    val subsumed = scala.collection.mutable.HashSet[T]()
    def loop(dags: Set[Dag[T]], targets: Set[T]): Set[T] = {
      dags.foldLeft[Set[T]](Set()) {
        case (acc, dag) =>
          dag match {
            case Leaf(value) if targets.contains(value) =>
              if (subsumed.contains(value)) acc else acc.+(value)
            case Leaf(_) => acc
            case p @ Parent(value, children) if targets.contains(value) =>
              val transitiveChildren = transitives.get(p).getOrElse{
                val transitives0 = children.flatMap(Dag.dfs(_))
                transitives.+=(p -> transitives0)
                transitives0
              }

              subsumed.++=(transitiveChildren)
              acc.--(transitiveChildren).+(value)
            case Parent(_, children) => loop(children.toSet, targets) ++ acc
          }
      }
    }

    loop(dags.toSet, targets)
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
    val traversed = new scala.collection.mutable.HashMap[Dag[T], List[String]]()
    def register(k: Dag[T], v: List[String]): List[String] = { traversed.put(k, v); v }

    def recordEdges(dag: Dag[T]): List[String] = {
      traversed.get(dag) match {
        case Some(cached) => cached
        case None =>
          val shows = dag match {
            case Leaf(value) => List(Show.shows(value))
            case Parent(value, dependencies) =>
              val downstream = dependencies.map(recordEdges).flatten
              val prettyPrintedDeps = dependencies.map {
                case Leaf(value) => Show.shows(value)
                case Parent(value, _) => Show.shows(value)
              }
              val target = Show.shows(value)
              prettyPrintedDeps.map(dep => s""""$dep" -> "$target";""") ++ downstream
          }
          register(dag, shows)
      }
    }

    // Inefficient implementation, but there is no need for efficiency here.
    val all = Dag.dfs(dag).toSet
    val nodes = all.map { node =>
      val id = Show.shows(node)
      s""""$id" [label="$id"];"""
    }

    val edges = recordEdges(dag)
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
