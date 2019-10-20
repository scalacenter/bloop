package bloop.engine.tasks.compilation

import bloop.engine.Dag
import bloop.data.{Project}
import java.util.concurrent.ConcurrentHashMap
import bloop.engine.tasks.compilation.CompileDefinitions.CompileTraversal

sealed trait CompileClientStore {
  def resetTraversalFor(dag: Dag[Project]): Unit
  def findPreviousTraversalOrAddNew(
      dag: Dag[Project],
      traversal: CompileTraversal
  ): Option[CompileTraversal]
}

object CompileClientStore {
  object NoStore extends CompileClientStore {
    def resetTraversalFor(dag: Dag[Project]): Unit = ()
    def findPreviousTraversalOrAddNew(
        dag: Dag[Project],
        traversal: CompileTraversal
    ): Option[CompileTraversal] = None
  }

  final class ConcurrentStore extends CompileClientStore {
    private[this] val traversals = new ConcurrentHashMap[Dag[Project], CompileTraversal]()

    def resetTraversalFor(dag: Dag[Project]): Unit = {
      traversals.computeIfPresent(dag, (_: Dag[Project], _: CompileTraversal) => null); ()
    }

    def findPreviousTraversalOrAddNew(
        dag: Dag[Project],
        traversal: CompileTraversal
    ): Option[CompileTraversal] = {
      Option(traversals.putIfAbsent(dag, traversal)).orElse(Some(traversal))
    }
  }
}
