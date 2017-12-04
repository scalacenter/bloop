package bloop.engine

final case class Project(name: String, dependencies: List[String])

sealed trait DAG[T]
final case class Leaf[T](value: T) extends DAG[T]
final case class Parent[T](value: T, children: List[DAG[T]]) extends DAG[T]

object DAG {
  private[bloop] class RecursiveCycle(path: List[Project])
      extends Exception(s"Not a DAG, cycle detected in ${path.map(_.name).mkString(" -> ")} ")
  def fromMap(projectsMap: Map[String, Project]): List[DAG[Project]] = {
    val visited = new scala.collection.mutable.HashMap[Project, DAG[Project]]()
    val visiting = new scala.collection.mutable.LinkedHashSet[Project]()
    val dependees = new scala.collection.mutable.HashSet[DAG[Project]]()
    val projects = projectsMap.values.toList
    def loop(project: Project, dependee: Boolean): DAG[Project] = {
      def markVisited(dag: DAG[Project]): DAG[Project] = { visited.+=(project -> dag); dag }
      def register(dag: DAG[Project]): DAG[Project] = {
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

  def dfs[T](dag: DAG[T]): List[T] = {
    def loop(dag: DAG[T], acc: List[T]): List[T] = {
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
  def toDotGraph(dags: List[DAG[Project]]): String = {
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
