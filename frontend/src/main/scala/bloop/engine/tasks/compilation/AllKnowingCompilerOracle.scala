package bloop.engine.tasks.compilation

import java.io.File

import bloop.data.Project
import bloop.{Compiler, CompilerOracle}

/**
 * An all-knowing compiler oracle knows all relevant information happening
 * at the bloop build server level instead of at the client level. Therefore,
 * it is an entity capable of synchronizing and answering questions critical
 * for deduplicating and running compilations concurrently.
 *
 * A compiler oracle is an immutable class and every project compilation gets
 * its own. However, the oracle is fed information that is stored globally
 * such as those ongoing compilations triggered by all clients.
 */
final class AllKnowingCompilerOracle(
    scheduledCompilations: List[PartialSuccess],
    transientClassesDirectories: Map[Project, File],
    ongoingCompilations: CompileGraph.RunningCompilationsInAllClients
) extends CompilerOracle[PartialSuccess] {

  /**
   * A question to the oracle about what are the java sources of those
   * projects that are still under compilation. This is necessary when
   * build pipelining is enabled and it's used in `BloopHighLevelCompiler`.
   */
  override def askForJavaSourcesOfIncompleteCompilations: List[File] = {
    scheduledCompilations.flatMap { r =>
      val runningPromise = r.completeJava
      if (runningPromise.isDone) Nil
      else r.bundle.javaSources.map(_.toFile)
    }
  }

  override def askForClassesDirectory(
      inputs: Compiler.RequestInputs
  ): File = {
    ???
  }

  override def learnScheduledCompilations(
      scheduled: List[PartialSuccess]
  ): CompilerOracle[PartialSuccess] = {
    new AllKnowingCompilerOracle(
      scheduled ::: scheduledCompilations,
      transientClassesDirectories,
      ongoingCompilations
    )
  }
}

object AllKnowingCompilerOracle {
  def empty(
      ongoingCompilations: CompileGraph.RunningCompilationsInAllClients
  ): AllKnowingCompilerOracle = {
    new AllKnowingCompilerOracle(Nil, Map.empty, ongoingCompilations)
  }
}
