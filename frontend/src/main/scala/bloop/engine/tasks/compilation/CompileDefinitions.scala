package bloop.engine.tasks.compilation

import bloop.CompileProducts
import bloop.PartialCompileProducts
import bloop.data.Project
import bloop.engine.Dag
import bloop.task.Task

object CompileDefinitions {
  type ProjectId = String
  type CanBeDeduplicated = Boolean
  type CompileRun = Dag[PartialCompileResult]
  type CompileTraversal = Task[CompileRun]

  type BundleProducts = Either[PartialCompileProducts, CompileProducts]
  case class BundleInputs(
      project: Project,
      dag: Dag[Project],
      dependentProducts: Map[Project, BundleProducts]
  )

}
