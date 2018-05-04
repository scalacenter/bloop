// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.io.File

import monix.eval.Task
import sbt.internal.inc._
import sbt.util.Logger
import xsbti.compile.{ClassFileManager, DependencyChanges, IncOptions}
import sbt.internal.inc.IncrementalNameHashingImpl

/**
 * Defines Bloop's version of `IncrementalNameHashing` that extends Zinc's original
 * incremental name hashing implementation to re-use all its logic from Zinc.
 *
 * The policy of this class is to serve as a wrapper of Zinc's incremental algorithm
 * but any change meant to improve the precision of it *must be* implemented in Zinc.
 *
 * This class serves two main purposes (now and in the future):
 *
 * 1. Allows for the instrumentation of several parts of the algorithm. This will
 *    prove useful when we want to collect dependency data and explain why an
 *    incremental compilation took place; or to display better logs with Bloop's
 *    knowledge of the build graph (see https://github.com/scalacenter/bloop/issues/386)
 *
 * 2. To allow the implementation of a Monix task-based Zinc frontend that allows
 *    us to express the invalidation logic in terms of tasks.
 *
 *    This is required for a solid implementation of pipelined compilation that has
 *    different semantics for Scala and Java compilation, depending on the order.
 *    (Java compilation needs to block on upstream compiles, whereas Scala
 *    compilation can safely proceed). We want to express this without an explicit
 *    blocking so that we don't waste CPU time waiting on some other futures to finish.
 *
 * Note: As some of the implementation is private or final, we need to copy-paste
 * it here so that we can change its interface.
 *
 * @param log The logger in which the invalidation algorithm will log stuff.
 * @param options The incremental compiler options.
 */
private final class BloopNameHashing(log: Logger, options: IncOptions) extends IncrementalNameHashingImpl(log, options) {
  def entrypoint(
      invalidatedRaw: Set[String],
      modifiedSrcs: Set[File],
      allSources: Set[File],
      binaryChanges: DependencyChanges,
      lookup: ExternalLookup,
      previous: Analysis,
      compileTask: (Set[File], DependencyChanges) => Task[Analysis],
      manager: ClassFileManager,
      cycleNum: Int
  ): Task[Analysis] = {
    if (invalidatedRaw.isEmpty && modifiedSrcs.isEmpty) Task.now(previous)
    else {
      val invalidatedClassNames = invalidateClassNames(invalidatedRaw, previous)
      val invalidatedClassesSources = invalidatedClassNames.flatMap(previous.relations.definesClass)
      val sourcesToRecompile = expandSources(invalidatedClassesSources ++ modifiedSrcs, allSources)
      val prunedAnalysis = Incremental.prune(sourcesToRecompile, previous, manager)
      debug("********* Pruned: \n" + prunedAnalysis.relations + "\n*********")

      compileTask(sourcesToRecompile, binaryChanges).flatMap { freshAnalysis =>
        // Merge the analysis after the compilation succeeds
        val freshRelations = freshAnalysis.relations
        manager.generated(freshRelations.allProducts.toArray) // Required only for scalac, see Zinc
        debug(s"********* Fresh: \n ${freshRelations}\n*********")
        val newAnalysis = prunedAnalysis ++ freshAnalysis
        debug(s"********* Merged: \n${newAnalysis.relations}\n*********")

        if (sourcesToRecompile == allSources) Task.now(newAnalysis)
        else {
          val previousRelations = previous.relations
          val newRelations = newAnalysis.relations

          // Map back to classes to find out removed, added or renamed classes
          val classesFromPreviousAnalysis = modifiedSrcs.flatMap(previousRelations.classNames)
          val classesFromNewAnalysis = modifiedSrcs.flatMap(newRelations.classNames)
          val recompiledClasses =
            invalidatedClassNames ++ classesFromPreviousAnalysis ++ classesFromNewAnalysis

          val mapToOldAPI = previous.apis.internalAPI _
          val mapToNewAPI = newAnalysis.apis.internalAPI _
          val incChanges = changedIncremental(recompiledClasses, mapToOldAPI, mapToNewAPI)

          debug(s"\nChanges:\n${incChanges}")
          val classToSourceMapper = new ClassToSourceMapper(previousRelations, newRelations)
          val newInvalidatedClassNames = invalidateIncremental(newRelations, newAnalysis.apis, incChanges, recompiledClasses, cycleNum >= options.transitiveStep, classToSourceMapper.isDefinedInScalaSrc)

          val allInvalidatedClassNames =
            if (!lookup.shouldDoIncrementalCompilation(newInvalidatedClassNames, newAnalysis)) Set.empty[String]
            else newInvalidatedClassNames

          entrypoint(allInvalidatedClassNames, Set.empty, allSources, emptyChanges, lookup, newAnalysis, compileTask, manager, cycleNum + 1)
        }
      }
    }
  }

  private[this] def emptyChanges: DependencyChanges = new DependencyChanges {
    val modifiedBinaries = new Array[File](0)
    val modifiedClasses = new Array[String](0)
    def isEmpty = true
  }

  def invalidateClassNames(alreadyInvalidated: Set[String], previous: Analysis): Set[String] = {
    val invalidatedPackageObjects =
      this.invalidatedPackageObjects(alreadyInvalidated, previous.relations, previous.apis)
    if (invalidatedPackageObjects.nonEmpty)
      log.debug(s"Invalidated package objects: $invalidatedPackageObjects")
    alreadyInvalidated ++ invalidatedPackageObjects
  }

  def expandSources(invalidated: Set[File], all: Set[File]): Set[File] = {
    val recompileAllFraction = options.recompileAllFraction
    if (invalidated.size > all.size * recompileAllFraction) {
      log.debug("Recompiling all " + all.size + " sources: invalidated sources (" + invalidated.size + ") exceeded " + (recompileAllFraction * 100.0) + "% of all sources")
      all ++ invalidated // need the union because all doesn't contain removed sources
    } else invalidated
  }
}
