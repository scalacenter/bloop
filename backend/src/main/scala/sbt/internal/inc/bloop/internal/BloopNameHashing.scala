package sbt.internal.inc.bloop.internal

import scala.collection.parallel.immutable.ParVector

import bloop.task.Task

import _root_.bloop.tracing.BraveTracer
import _root_.bloop.util.HashedSource
import sbt.internal.inc._
import sbt.util.Logger
import xsbti.FileConverter
import xsbti.VirtualFile
import xsbti.VirtualFileRef
import xsbti.compile.ClassFileManager
import xsbti.compile.DependencyChanges
import xsbti.compile.IncOptions
import xsbti.compile.Output

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
    options: IncOptions,
    profiler: RunProfiler,
    tracer: BraveTracer
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
      initialChangedSources: Set[VirtualFileRef],
      allSources: Set[VirtualFile],
      binaryChanges: DependencyChanges,
      lookup: ExternalLookup,
      previous: Analysis,
      compileTask: (Set[VirtualFile], DependencyChanges) => Task[Analysis],
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
        mapInvalidationsToSources(
          classesToRecompile,
          initialChangedSources,
          allSources.map(v => v: VirtualFileRef),
          previous
        ).map {
          case f: VirtualFile => f
          case ref: VirtualFileRef => HashedSource.converter.toVirtualFile(ref)
        }

      recompileClasses(
        invalidatedSources.filter(allSources),
        invalidatedSources,
        binaryChanges,
        previous,
        compileTask,
        manager
      ).flatMap { current =>
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

          // Zinc invalidates dependents through recorded dependency edges, but a
          // source removed from the build and later re-added with unchanged
          // contents has no such edges from retained sources: they were erased
          // when it was removed. When a (re-)added source reintroduces a package
          // object, invalidate the retained sources that reference that package
          // so their implicit resolution is recomputed against it.
          val readdedPackageObjectInvalidations =
            invalidationsFromAddedPackageObjects(invalidatedSources, previous, current)

          val nextInvalidations = invalidateAfterInternalCompilation(
            current,
            newApiChanges,
            recompiledClasses,
            cycleNum >= options.transitiveStep,
            IncrementalCommon.comesFromScalaSource(previous.relations, Some(current.relations))
          ) ++ readdedPackageObjectInvalidations
          debug(s"Next invalidations: $nextInvalidations")

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

  import xsbti.compile.analysis.{ReadStamps, Stamp => XStamp}
  override def detectInitialChanges(
      sources: Set[VirtualFile],
      previousAnalysis: Analysis,
      stamps: ReadStamps,
      lookup: Lookup,
      converter: FileConverter,
      output: Output
  )(implicit equivS: Equiv[XStamp]): InitialChanges = {
    tracer.traceVerbose("detecting initial changes") { tracer =>
      // Copy pasting from IncrementalCommon to optimize/remove IO work
      val previous = previousAnalysis.stamps
      val previousRelations = previousAnalysis.relations

      val sourceChanges = tracer.traceVerbose("source changes") { _ =>
        lookup.changedSources(previousAnalysis).getOrElse {
          val previousSources = previous.allSources.toSet
          new UnderlyingChanges[VirtualFileRef] {
            private val sourceRefs = sources.map(f => f: VirtualFileRef)
            private val inBoth = sourceRefs.filter(previousSources)
            val removed = previousSources.filterNot(inBoth)
            val added = sourceRefs.filterNot(inBoth)
            val (changed, unmodified) = inBoth.partition { f =>
              // We compute hashes via xxHash in Bloop, so we adapt them to the zinc hex format
              val newStamp = f match {
                case hashed: HashedSource => BloopStamps.fromBloopHashToZincHash(hashed.bloopHash)
                case other =>
                  log.error(s"Expected file to be hashed previously: ${other}")
                  BloopStamps.forHash(other)
              }
              !equivS.equiv(previous.sources(f), newStamp)
            }
          }
        }
      }
      val removedProducts =
        lookup.removedProducts(previousAnalysis).getOrElse {
          new ParVector(previous.allProducts.toVector)
            .filter(p => {
              !equivS.equiv(previous.product(p), stamps.product(p))
            })
            .toVector
            .toSet
        }

      val changedBinaries: Set[VirtualFileRef] = tracer.traceVerbose("changed binaries") { _ =>
        lookup.changedBinaries(previousAnalysis).getOrElse {
          val detectChange = IncrementalCommon.isLibraryModified(
            false,
            lookup,
            previous,
            stamps,
            previousRelations,
            HashedSource.converter,
            log
          )
          previous.allLibraries.filter(detectChange).toSet
        }
      }

      val externalApiChanges: APIChanges = tracer.traceVerbose("external api changes") { _ =>
        val incrementalExternalChanges = {
          val previousAPIs = previousAnalysis.apis
          val externalFinder =
            lookup.lookupAnalyzedClass(_: String, None).getOrElse(APIs.emptyAnalyzedClass)
          detectAPIChanges(
            previousAPIs.allExternals,
            previousAPIs.externalAPI,
            externalFinder
          )
        }

        val changedExternalClassNames = incrementalExternalChanges.allModified.toSet
        if (!lookup.shouldDoIncrementalCompilation(changedExternalClassNames, previousAnalysis))
          new APIChanges(Nil)
        else incrementalExternalChanges
      }

      val init = InitialChanges(sourceChanges, removedProducts, changedBinaries, externalApiChanges)
      profiler.registerInitial(init)
      init
    }
  }

  def recompileClasses(
      currentInvalidatedSources: Set[VirtualFile],
      invalidatedSources: Set[VirtualFile],
      binaryChanges: DependencyChanges,
      previous: Analysis,
      compileTask: (Set[VirtualFile], DependencyChanges) => Task[Analysis],
      classfileManager: ClassFileManager
  ): Task[Analysis] = {
    val pruned =
      IncrementalCommon.pruneClassFilesOfInvalidations(
        invalidatedSources,
        previous,
        classfileManager,
        HashedSource.converter
      )
    debug("********* Pruned: \n" + pruned.relations + "\n*********")
    compileTask(currentInvalidatedSources, binaryChanges).map { fresh =>
      debug("********* Fresh: \n" + fresh.relations + "\n*********")

      /* This is required for both scala compilation and forked java compilation, despite
       *  being redundant for the most common Java compilation (using the local compiler). */
      classfileManager.generated(fresh.relations.allProducts.collect {
        case v: VirtualFile => v
      }.toArray)

      val merged = pruned ++ fresh
      debug("********* Merged: \n" + merged.relations + "\n*********")
      merged
    }
  }

  /**
   * Computes extra invalidations for package objects that were just (re-)added.
   *
   * This is a Bloop-specific workaround for source-set churn (sources removed
   * and re-added through build/config changes), not a general precision
   * improvement — those belong in Zinc, per the note on [[BloopNameHashing]].
   * When a source is removed, the dependency edges from retained sources onto
   * its symbols are erased, and re-adding it does not restore them, so Zinc's
   * edge-based invalidation leaves those retained sources compiled against the
   * stale state. A package object is the common trigger because it contributes
   * implicits to the implicit scope of its whole package.
   *
   * When a recompiled source produces a `*.package` class absent from the
   * previous analysis, we invalidate every class in that package (each has the
   * package object in its implicit scope) and every class that references one of
   * those classes. This is deliberately conservative — re-adding a package
   * object recompiles its whole package — which is an acceptable tradeoff
   * because source-set churn is rare and correctness outweighs incrementality
   * here. It only fires for added package objects, so ordinary and clean
   * compiles are unaffected.
   *
   * Coverage is bounded by recorded dependency edges. A retained source that
   * imports the package solely for package-object members (without referencing
   * any of the package's classes) holds no such edge in the stale analysis and
   * is therefore not detected; catching it would require import/used-name
   * information that the erased analysis no longer carries.
   */
  private def invalidationsFromAddedPackageObjects(
      recompiledSources: Set[VirtualFile],
      previous: Analysis,
      current: Analysis
  ): Set[String] = {
    def packageOf(className: String): String = {
      val i = className.lastIndexOf('.')
      if (i < 0) "" else className.substring(0, i)
    }

    val knownClasses = previous.relations.classes._2s
    val addedPackageObjects = recompiledSources
      .flatMap(src => current.relations.classNames(src))
      .filter(name => name.endsWith(".package") && !knownClasses.contains(name))

    if (addedPackageObjects.isEmpty) Set.empty
    else {
      val allClasses = current.relations.classes._2s
      addedPackageObjects.flatMap { packageObject =>
        val pkg = packageOf(packageObject)
        // Every class in the package has the package object in its implicit
        // scope, so any of them may now resolve implicits differently.
        val classesInPackage =
          allClasses.filter(name => name != packageObject && packageOf(name) == pkg)
        // Classes that reference one of the package's classes (e.g. importers
        // that use its types). This is the most we can detect from recorded
        // edges — see the method doc for the bound on coverage.
        val packageClassUsers =
          classesInPackage.flatMap(name => current.relations.usesInternalClass(name))
        classesInPackage ++ packageClassUsers
      }
    }
  }
}
