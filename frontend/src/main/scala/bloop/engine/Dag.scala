package bloop.engine
import java.{util => ju}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import bloop.data.Project
import bloop.util.CacheHashCode

import scalaz.Show

/**
 * A [[Dag]] is a Directed Acyclic Graph where each node contains a value of T
 */
sealed trait Dag[T]
final case class Leaf[T](value: T) extends Dag[T] with CacheHashCode
final case class Parent[T](value: T, children: List[Dag[T]]) extends Dag[T] with CacheHashCode
final case class Aggregate[T](dags: List[Dag[T]]) extends Dag[T] with CacheHashCode

object Dag {
  case class RecursiveTrace(visited: List[Project])
  case class DagResult(
      dags: List[Dag[Project]],
      missingDependencies: Map[Project, List[String]],
      traces: List[RecursiveTrace]
  )

  def fromMap(projectsMap: Map[String, Project]): DagResult = {
    val missingDeps = new scala.collection.mutable.HashMap[Project, List[String]]()
    val visited = new scala.collection.mutable.HashMap[Project, Dag[Project]]()
    val visiting = new scala.collection.mutable.LinkedHashSet[Project]()
    val dependents = new scala.collection.mutable.HashSet[Dag[Project]]()
    val projects = projectsMap.values.toList
    val traces = new ListBuffer[RecursiveTrace]()
    def loop(project: Project, dependent: Boolean): Option[Dag[Project]] = {
      def markVisited(dag: Dag[Project]): Dag[Project] = { visited.+=(project -> dag); dag }
      def register(dagOpt: Option[Dag[Project]]): Option[Dag[Project]] = {
        dagOpt.map { dag =>
          if (dependent && !dependents.contains(dag)) dependents.+=(dag); dag
        }
      }

      register {
        // Check that there are no cycles
        if (visiting.contains(project)) {
          // We register the recursive cycle and return none
          traces.+=(RecursiveTrace(visiting.toList :+ project))
          None
        } else {
          visiting.+=(project)

          val dag = visited.get(project).getOrElse {
            markVisited {
              project match {
                case leaf if project.dependencies.isEmpty => Leaf(leaf)
                case parent =>
                  val childrenNodes = project.dependencies.iterator.flatMap { name =>
                    projectsMap.get(name) match {
                      case Some(project) => List(project)
                      case None =>
                        val newMissingDeps = missingDeps.get(parent) match {
                          case Some(prev) => name :: prev
                          case None => name :: Nil
                        }

                        missingDeps.+=((parent, newMissingDeps))
                        Nil
                    }
                  }
                  Parent(parent, childrenNodes.flatMap(loop(_, true)).toList)
              }
            }
          }

          visiting.-=(project)
          Some(dag)
        }
      }
    }

    // Traverse through all the projects and only get the root nodes
    val dags = projects.flatMap(loop(_, false))
    DagResult(
      dags.filterNot(node => dependents.contains(node)),
      missingDeps.toMap,
      traces.toList
    )
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
    val cache = mutable.Map.empty[T, Option[List[Dag[T]]]]
    def loop(dags: List[Dag[T]], targets: Set[T]): Option[List[Dag[T]]] = {
      dags.foldLeft[Option[List[Dag[T]]]](None) {
        case (found: Some[List[Dag[T]]], _) => found
        case (acc, dag) =>
          def aggregate(dag: Dag[T]): Option[List[Dag[T]]] = acc match {
            case Some(dags) => Some(dag :: dags)
            case None => Some(List(dag))
          }
          dag match {
            case Leaf(value) if targets.contains(value) => aggregate(dag)
            case Leaf(_) => None
            case Parent(value, _) if targets.contains(value) => aggregate(dag)
            case Parent(value, children) => cache.getOrElseUpdate(value, loop(children, targets))
            case Aggregate(dags) => loop(dags, targets)
          }
      }
    }
    loop(dags, targets)
  }

  def transitive[T](dag: Dag[T]): List[Dag[T]] = {
    dag match {
      case leaf: Leaf[T] => List(leaf)
      case p @ Parent(_, children) => p :: children.flatMap(transitive)
      case Aggregate(dags) => dags.flatMap(transitive)
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
    val isVisited = new ju.IdentityHashMap[Dag[T], Unit]()
    def loop(dags: Set[Dag[T]], targets: Set[T]): Set[T] = {
      dags.foldLeft[Set[T]](Set()) {
        case (acc, dag) =>
          if (isVisited.containsKey(dag)) acc
          else {
            isVisited.put(dag, ())
            dag match {
              case Leaf(value) if targets.contains(value) =>
                if (subsumed.contains(value)) acc else acc.+(value)
              case Leaf(_) => acc
              case p @ Parent(value, children) if targets.contains(value) =>
                val transitiveChildren = transitives.get(p).getOrElse {
                  val transitives0 = children.flatMap(Dag.dfs(_))
                  transitives.+=(p -> transitives0)
                  transitives0
                }

                subsumed.++=(transitiveChildren)
                acc.--(transitiveChildren).+(value)
              case Parent(_, children) => loop(children.toSet, targets) ++ acc
              case Aggregate(dags) => loop(dags.toSet, targets)
            }
          }
      }
    }

    loop(dags.toSet, targets)
  }

  def directDependencies[T](dags: List[Dag[T]]): List[T] = {
    dags.foldLeft(List.empty[T]) {
      case (acc, dag) =>
        dag match {
          case Leaf(value) => value :: acc
          case Parent(value, _) => value :: acc
          case Aggregate(dags) => directDependencies(dags)
        }
    }
  }

  /**
   * Represent the result of [[inverseDependencies()]].
   *
   * @param reduced The minimal set of nodes that subsume `targets`.
   * @param allCascaded The set of all nodes that are transitive inverse
   *                    dependencies of all `targets`.
   */
  case class InverseDependencies[T](reduced: List[T], allCascaded: List[T])

  /**
   * Given a forest of DAGs and a collection of targets, this method traverses
   * the forest to find all dependencies that are parents of the targets
   * transitively. It returns the maximally minimal set of nodes that cover all
   * inverse dependencies as well as all the transitive inverse dependencies.
   */
  def inverseDependencies[T](dags: List[Dag[T]], targets: List[T]): InverseDependencies[T] = {
    val roots = scala.collection.mutable.HashSet[T]()
    val cascaded = scala.collection.mutable.HashSet[T]()
    val nonVisitedTargets = scala.collection.mutable.HashSet[Dag[T]]()

    var foundTargets = 0
    val targetsSet = targets.toSet
    val expectedTargetSize = targetsSet.size

    case class Trace(root: Option[T], recentlyVisited: List[T]) {
      def addToTrace(elem: T): Trace = {
        root match {
          case None => Trace(Some(elem), recentlyVisited)
          case Some(_) => Trace(root, elem :: recentlyVisited)
        }
      }
    }

    def loopDag(dag: Dag[T], trace: Trace): Unit = {
      if (nonVisitedTargets.contains(dag)) ()
      else {
        def processTrace(trace: Trace): Unit = {
          trace.root match {
            case Some(root) =>
              // Remove those subsumed by the first root
              trace.recentlyVisited.foreach { x =>
                // Try to remove it only if `x` was seen before
                if (cascaded.contains(x)) roots.remove(x)
                else cascaded.+=(x)
              }

              // Proceed if this node hasn't been processed before
              if (cascaded.contains(root)) ()
              else {
                cascaded.+=(root)
                // Add as root the outermost node
                roots.+=(root)
              }
            case None => ()
          }
        }

        dag match {
          case Leaf(target) if targetsSet.contains(target) =>
            foundTargets += 1
            processTrace(trace.addToTrace(target))

          case Parent(target, children) if targetsSet.contains(target) =>
            foundTargets += 1
            val newTrace = trace.addToTrace(target)
            processTrace(newTrace)

            // If we haven't found all targets yeah, keep recursing to populate all cascaded nodes
            if (foundTargets != expectedTargetSize) {
              children.foreach(dag => loopDag(dag, newTrace))
            }

          // Stop when we find a cascaded node but process all nodes we came from
          case Parent(target, _) if cascaded.contains(target) =>
            val newTrace = trace.addToTrace(target)
            processTrace(newTrace)

          /* Continue the traversal through the DAG with new traces. */

          case Leaf(_) => nonVisitedTargets.+=(dag)
          case Parent(elem, children) =>
            val newTrace = trace.addToTrace(elem)
            children.foreach(dag => loopDag(dag, newTrace))
            nonVisitedTargets.+=(dag)
          case Aggregate(dags) =>
            dags.foreach(dag => loopDag(dag, trace))
            nonVisitedTargets.+=(dag)
        }
      }
    }

    dags.foreach(dag => loopDag(dag, Trace(None, Nil)))
    InverseDependencies(roots.toList, cascaded.toList)
  }

  /**
   * Depth first search
   */
  def dfs[T](dag: Dag[T]): List[T] = {
    val visited = scala.collection.mutable.HashSet[Dag[T]]()
    val buffer = new ListBuffer[T]()
    def loop(dag: Dag[T]): Unit = {
      if (visited.contains(dag)) () // We're already in the buffer
      else {
        visited.add(dag)
        dag match {
          case Leaf(value) => buffer.+=(value)
          case Aggregate(dags) =>
            dags.foreach(loop(_))
          case Parent(value, children) =>
            buffer.+=(value)
            children.foreach(loop(_))
        }
      }
    }

    loop(dag)
    buffer.result()
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
            case Aggregate(dags) => dags.map(recordEdges).flatten
            case Parent(value, dependencies) =>
              val downstream = dependencies.map(recordEdges).flatten
              val prettyPrintedDeps = dependencies.flatMap {
                case Leaf(value) => List(Show.shows(value))
                case Parent(value, _) => List(Show.shows(value))
                case Aggregate(_) => Nil
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
