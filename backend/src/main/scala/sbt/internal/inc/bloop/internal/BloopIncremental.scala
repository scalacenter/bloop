// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.io.File
import java.nio.file.Path

import bloop.UniqueCompileInputs
import bloop.task.Task
import bloop.tracing.BraveTracer

import sbt.internal.inc.Analysis
import sbt.internal.inc.InvalidationProfiler
import sbt.internal.inc.Lookup
import sbt.internal.inc.PlainVirtualFileConverter
import sbt.util.Logger
import xsbti.AnalysisCallback
import xsbti.VirtualFile
import xsbti.api.AnalyzedClass
import xsbti.compile._
import xsbti.compile.analysis.ReadStamps
import xsbti.compile.analysis.Stamp

object BloopIncremental {
  type CompileFunction =
    (Set[VirtualFile], DependencyChanges, AnalysisCallback, ClassFileManager) => Task[Unit]

  private val converter = PlainVirtualFileConverter.converter

  def compile(
      sources: Iterable[VirtualFile],
      uniqueInputs: UniqueCompileInputs,
      lookup: Lookup,
      compile: CompileFunction,
      previous0: CompileAnalysis,
      output: Output,
      log: Logger,
      options: IncOptions,
      manager: ClassFileManager,
      tracer: BraveTracer,
      isHydraEnabled: Boolean
  ): Task[(Boolean, Analysis)] = {
    def getExternalAPI(lookup: Lookup): (Path, String) => Option[AnalyzedClass] = { (_: Path, binaryClassName: String) =>
      lookup.lookupAnalysis(binaryClassName) flatMap {
        case (analysis: Analysis) =>
          val sourceClassName =
            analysis.relations.productClassName.reverse(binaryClassName).headOption
          sourceClassName flatMap analysis.apis.internal.get
      }
    }

    val current = BloopStamps.initial
    val externalAPI = getExternalAPI(lookup)
    val previous = previous0 match { case a: Analysis => a }
    val previousRelations = previous.relations
    val internalBinaryToSourceClassName = (binaryClassName: String) => previousRelations.productClassName.reverse(binaryClassName).headOption

    val builder: () => IBloopAnalysisCallback = {
      if (!isHydraEnabled) () => new BloopAnalysisCallback(internalBinaryToSourceClassName, externalAPI, current, output, options, manager)
      else
        () => new ConcurrentAnalysisCallback(internalBinaryToSourceClassName, externalAPI, current, output, options, manager)
    }
    // We used to catch for `CompileCancelled`, but we prefer to propagate it so that Bloop catches it
    compileIncremental(sources, uniqueInputs, lookup, previous, current, compile, builder, log, output, options, manager, tracer)
  }

  def compileIncremental(
      sources: Iterable[VirtualFile],
      uniqueInputs: UniqueCompileInputs,
      lookup: Lookup,
      previous: Analysis,
      current: ReadStamps,
      compile: CompileFunction,
      callbackBuilder: () => IBloopAnalysisCallback,
      log: sbt.util.Logger,
      output: Output,
      options: IncOptions,
      manager: ClassFileManager,
      tracer: BraveTracer,
      // TODO(jvican): Enable profiling of the invalidation algorithm down the road
      profiler: InvalidationProfiler = InvalidationProfiler.empty
  )(implicit equivS: Equiv[Stamp]): Task[(Boolean, Analysis)] = {
    val setOfSources = sources.toSet
    val incremental = new BloopNameHashing(log, uniqueInputs, options, profiler.profileRun, tracer)
    val initialChanges = incremental.detectInitialChanges(setOfSources, previous, current, lookup, converter, output)
    def isJrt(path: Path) = path.getFileSystem.provider().getScheme == "jrt"
    val binaryChanges = new DependencyChanges {
      val modifiedLibraries = initialChanges.libraryDeps.toArray

      val modifiedBinaries: Array[File] = modifiedLibraries
        .map(converter.toPath(_))
        .collect {
          // jrt path is neither a jar nor a normal file
          case path if !isJrt(path) =>
            path.toFile()
        }
        .distinct
      val modifiedClasses = initialChanges.external.allModified.toArray
      def isEmpty = modifiedLibraries.isEmpty && modifiedClasses.isEmpty
    }

    val (initialInvClasses, initialInvSources) =
      incremental.invalidateInitial(previous.relations, initialChanges)

    if (initialInvClasses.nonEmpty || initialInvSources.nonEmpty) {
      if (initialInvSources == sources) incremental.log.debug("All sources are invalidated.")
      else {
        incremental.log.debug(
          "All initially invalidated classes: " + initialInvClasses + "\n" +
            "All initially invalidated sources:" + initialInvSources + "\n"
        )
      }
    }

    val analysisTask = {
      val doCompile = (srcs: Set[VirtualFile], changes: DependencyChanges) => {
        for {
          callback <- Task.now(callbackBuilder())
          _ <- compile(srcs, changes, callback, manager)
        } yield callback.get
      }

      incremental.entrypoint(initialInvClasses, initialInvSources, setOfSources, binaryChanges, lookup, previous, doCompile, manager, 1)
    }

    analysisTask.materialize.map {
      case scala.util.Success(analysis) =>
        manager.complete(true)
        (initialInvClasses.nonEmpty || initialInvSources.nonEmpty, analysis)
      case scala.util.Failure(e) =>
        manager.complete(false)
        throw e
    }
  }
}
