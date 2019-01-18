package sbt.internal.inc.bloop.internal

import java.io.File

import monix.eval.Task
import sbt.util.Logger
import sbt.internal.inc._
import _root_.bloop.reporter.ZincReporter
import xsbti.compile.{ClassFileManager, DependencyChanges, IncOptions}

/**
 * Defines Bloop's version of `IncrementalNameHashing` that extends Zinc's original
 * incremental name hashing implementation to re-use all its logic from Zinc.
 *
 * The policy of this class is to serve as a wrapper of Zinc's incremental algorithm
 * but any change meant to improve the precision of it *must be* implemented in Zinc.
 *
 * This class allows the implementation of a Monix task-based Zinc frontend that allows
 * us to express the invalidation logic in terms of tasks. This is required for a solid
 * implementation of pipelined compilation.
 *
 * @param log The logger in which the invalidation algorithm will log stuff.
 * @param options The incremental compiler options.
 * @param profiler The profiler used for the incremental invalidation algorithm.
 */
private final class BloopNameHashing(
    log: Logger,
    reporter: ZincReporter,
    options: IncOptions,
    profiler: RunProfiler
) extends IncrementalNameHashingCommon(log, options, profiler) {

  /**
   * Compile a project as many times as it is required incrementally. This logic is the start
   * point of the incremental compiler and the place where all the invalidation logic happens.
   *
   * The current logic does merge the compilation step and the analysis step, by making them
   * execute sequentially. There are cases where, for performance reasons, build tools and
   * users of Zinc may be interested in separating the two. If this is the case, the user needs
   * to reimplement this logic by copy pasting this logic and relying on the utils defined
   * in `IncrementalCommon`.
   *
   * @param invalidatedClasses The invalidated classes either initially or by a previous cycle.
   * @param initialChangedSources The initial changed sources by the user, empty if previous cycle.
   * @param allSources All the sources defined in the project and compiled in the first iteration.
   * @param binaryChanges The initially detected changes derived from [[InitialChanges]].
   * @param lookup The lookup instance to query classpath and analysis information.
   * @param previous The last analysis file known of this project.
   * @param doCompile A function that compiles a project and returns an analysis file.
   * @param manager The manager that takes care of class files in compilation.
   * @param cycleNum The counter of incremental compiler cycles.
   * @return A fresh analysis file after all the incremental compiles have been run.
   */
  def entrypoint(
      invalidatedClasses: Set[String],
      initialChangedSources: Set[File],
      allSources: Set[File],
      binaryChanges: DependencyChanges,
      lookup: ExternalLookup,
      previous: Analysis,
      compileTask: (Set[File], DependencyChanges) => Task[Analysis],
      manager: ClassFileManager,
      cycleNum: Int
  ): Task[Analysis] = {
    if (invalidatedClasses.isEmpty && initialChangedSources.isEmpty) Task.now(previous)
    else {
      // Compute all the invalidated classes by aggregating invalidated package objects
      val invalidatedByPackageObjects =
        invalidatedPackageObjects(invalidatedClasses, previous.relations, previous.apis)
      val classesToRecompile = invalidatedClasses ++ invalidatedByPackageObjects

      // Computes which source files are mapped to the invalidated classes and recompile them
      val invalidatedSources =
        mapInvalidationsToSources(classesToRecompile, initialChangedSources, allSources, previous)

      recompileClasses(invalidatedSources, binaryChanges, previous, compileTask, manager).flatMap {
        current =>
          // Return immediate analysis as all sources have been recompiled
          if (invalidatedSources == allSources) Task.now(current)
          else {
            val recompiledClasses: Set[String] = {
              // Represents classes detected as changed externally and internally (by a previous cycle)
              classesToRecompile ++
                // Maps the changed sources by the user to class names we can count as invalidated
                initialChangedSources.flatMap(previous.relations.classNames) ++
                initialChangedSources.flatMap(current.relations.classNames)
            }

            val newApiChanges =
              detectAPIChanges(
                recompiledClasses,
                previous.apis.internalAPI,
                current.apis.internalAPI
              )
            debug("\nChanges:\n" + newApiChanges)
            val nextInvalidations = invalidateAfterInternalCompilation(
              current.relations,
              newApiChanges,
              recompiledClasses,
              cycleNum >= options.transitiveStep,
              IncrementalCommon.comesFromScalaSource(previous.relations, Some(current.relations))
            )

            val continue = lookup.shouldDoIncrementalCompilation(nextInvalidations, current)

            profiler.registerCycle(
              invalidatedClasses,
              invalidatedByPackageObjects,
              initialChangedSources,
              invalidatedSources,
              recompiledClasses,
              newApiChanges,
              nextInvalidations,
              continue
            )

            entrypoint(
              if (continue) nextInvalidations else Set.empty,
              Set.empty,
              allSources,
              IncrementalCommon.emptyChanges,
              lookup,
              current,
              compileTask,
              manager,
              cycleNum + 1
            )
          }
      }
    }
  }

  def recompileClasses(
      sources: Set[File],
      binaryChanges: DependencyChanges,
      previous: Analysis,
      compileTask: (Set[File], DependencyChanges) => Task[Analysis],
      classfileManager: ClassFileManager
  ): Task[Analysis] = {
    val pruned =
      IncrementalCommon.pruneClassFilesOfInvalidations(sources, previous, classfileManager)
    debug("********* Pruned: \n" + pruned.relations + "\n*********")
    compileTask(sources, binaryChanges).map { fresh =>
      debug("********* Fresh: \n" + fresh.relations + "\n*********")

      /* This is required for both scala compilation and forked java compilation, despite
       *  being redundant for the most common Java compilation (using the local compiler). */
      classfileManager.generated(fresh.relations.allProducts.toArray)

      val merged = pruned ++ fresh
      debug("********* Merged: \n" + merged.relations + "\n*********")
      merged
    }
  }
}
