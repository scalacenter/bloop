package bloop.engine.tasks.compilation

import java.io.File

import bloop.data.Project
import bloop.{Compiler, CompilerOracle}

/**
 * An immutable compiler oracle knows all information concerning compilations
 * in a given run and by other clients. The oracle is an entity capable of
 * synchronizing and answering questions critical for deduplicating and running
 * compilations concurrently.
 *
 * The compiler oracle is created every time a project compilation is scheduled.
 * Together with global information such as all the ongoing compilations happening
 * in the build server, it receives local data from the compiler scheduler.
 */
final class ImmutableCompilerOracle(
    scheduledCompilations: List[PartialSuccess]
) extends CompilerOracle {

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
}

object ImmutableCompilerOracle {
  def empty(): ImmutableCompilerOracle = {
    new ImmutableCompilerOracle(Nil)
  }
}
