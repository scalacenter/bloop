package bloop.engine.tasks.compilation

import monix.eval.Task
import bloop.engine.Dag
import bloop.PartialCompileProducts
import bloop.CompileProducts
import bloop.data.Project
import java.io.File
import xsbti.compile.PreviousResult
import scala.concurrent.Promise
import bloop.JavaSignal

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
