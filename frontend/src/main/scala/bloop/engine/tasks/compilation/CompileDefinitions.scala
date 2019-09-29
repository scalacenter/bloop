package bloop.engine.tasks.compilation

import monix.eval.Task
import bloop.engine.Dag
import bloop.PartialCompileProducts
import bloop.CompileProducts
import bloop.data.Project
import bloop.CompilerOracle
import java.io.File
import xsbti.compile.PreviousResult
import scala.concurrent.Promise
import bloop.JavaSignal
import xsbti.compile.Signature

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

  case class PipelineInputs(
      irPromise: Promise[Array[Signature]],
      finishedCompilation: Promise[Option[CompileProducts]],
      completeJava: Promise[Unit],
      transitiveJavaSignal: Task[JavaSignal],
      separateJavaAndScala: Boolean
  )
}
