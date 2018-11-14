// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.io.File
import java.net.URI
import java.util.Optional
import java.util.concurrent.CompletableFuture

import monix.eval.Task
import sbt.internal.inc.{Analysis, InvalidationProfiler, Lookup, Stamper, Stamps, AnalysisCallback => AnalysisCallbackImpl}
import sbt.util.Logger
import xsbti.AnalysisCallback
import xsbti.api.AnalyzedClass
import xsbti.compile.analysis.{ReadStamps, Stamp}
import xsbti.compile._

object BloopIncremental {
  type CompileFunction =
    (Set[File], DependencyChanges, AnalysisCallback, ClassFileManager) => Task[Unit]
  def compile(
      sources: Iterable[File],
      lookup: Lookup,
      compile: CompileFunction,
      previous0: CompileAnalysis,
      output: Output,
      log: Logger,
      options: IncOptions,
      irPromise: CompletableFuture[Array[IR]]
  ): Task[(Boolean, Analysis)] = {
    def getExternalAPI(lookup: Lookup): (File, String) => Option[AnalyzedClass] = { (_: File, binaryClassName: String) =>
      lookup.lookupAnalysis(binaryClassName) flatMap {
        case (analysis: Analysis) =>
          val sourceClassName =
            analysis.relations.productClassName.reverse(binaryClassName).headOption
          sourceClassName flatMap analysis.apis.internal.get
      }
    }

    val externalAPI = getExternalAPI(lookup)
    val current = Stamps.initial(Stamper.forLastModified, Stamper.forHash, Stamper.forLastModified)

    val previous = previous0 match { case a: Analysis => a }
    val previousRelations = previous.relations
    val internalBinaryToSourceClassName = (binaryClassName: String) => previousRelations.productClassName.reverse(binaryClassName).headOption
    val internalSourceToClassNamesMap: File => Set[String] = (f: File) => previousRelations.classNames(f)

    val builder = new AnalysisCallbackImpl.Builder(internalBinaryToSourceClassName, internalSourceToClassNamesMap, externalAPI, current, output, options, irPromise)
    // We used to catch for `CompileCancelled`, but we prefer to propagate it so that Bloop catches it
    compileIncremental(sources, lookup, previous, current, compile, builder, log, options)
  }

  def compileIncremental(
      sources: Iterable[File],
      lookup: Lookup,
      previous: Analysis,
      current: ReadStamps,
      compile: CompileFunction,
      callbackBuilder: AnalysisCallbackImpl.Builder,
      log: sbt.util.Logger,
      options: IncOptions,
      // TODO(jvican): Enable profiling of the invalidation algorithm down the road
      profiler: InvalidationProfiler = InvalidationProfiler.empty
  )(implicit equivS: Equiv[Stamp]): Task[(Boolean, Analysis)] = {
    def manageClassfiles[T](options: IncOptions)(run: ClassFileManager => T): T = {
      import sbt.internal.inc.{ClassFileManager => ClassFileManagerImpl}
      val classfileManager = ClassFileManagerImpl.getClassFileManager(options)
      val result =
        try run(classfileManager)
        catch { case e: Throwable => classfileManager.complete(false); throw e }
      classfileManager.complete(true)
      result
    }

    val setOfSources = sources.toSet
    val incremental = new BloopNameHashing(log, options, profiler.profileRun)
    val initialChanges = incremental.detectInitialChanges(setOfSources, previous, current, lookup)
    val binaryChanges = new DependencyChanges {
      val modifiedBinaries = initialChanges.binaryDeps.toArray
      val modifiedClasses = initialChanges.external.allModified.toArray
      def isEmpty = modifiedBinaries.isEmpty && modifiedClasses.isEmpty
    }
    val (initialInvClasses, initialInvSources) =
      incremental.invalidateInitial(previous.relations, initialChanges)

    if (initialInvClasses.nonEmpty || initialInvSources.nonEmpty) {
      if (initialInvSources == sources) incremental.log.debug("All sources are invalidated.")
      else {
        incremental.log.debug(
          "All initially invalidated classes: " + initialInvClasses + "\n" +
            "All initially invalidated sources:" + initialInvSources + "\n")
      }
    }

    import sbt.internal.inc.{ClassFileManager => ClassFileManagerImpl}
    val classfileManager = ClassFileManagerImpl.getClassFileManager(options)
    val analysisTask = {
      val doCompile = (srcs: Set[File], changes: DependencyChanges) => {
        for {
          callback <- Task.now(callbackBuilder.build())
          _ <- compile(srcs, changes, callback, classfileManager)
        } yield callback.get
      }

      /* Normal Zinc happens to add the source infos of the previous result to the infos
       * of the new previous result. In constrast, we desire to only have the source infos
       * of those files that we have indeed compiled so that we can know from the outside
       * to which extent a new compilation overlaps with a previous compilation. This is
       * important whenever we want to know which warnings were not reported in the new
       * compilation but should be reported given that they are still present in the codebase.
       */
      val previousWithNoSourceInfos =
        previous.copy(infos = previous.infos -- previous.infos.allInfos.keys)
      try incremental.entrypoint(initialInvClasses, initialInvSources, setOfSources, binaryChanges, lookup, previousWithNoSourceInfos, doCompile, classfileManager, 1)
      catch { case e: Throwable => classfileManager.complete(false); throw e }
    }

    analysisTask.map { analysis =>
      classfileManager.complete(true)
      (initialInvClasses.nonEmpty || initialInvSources.nonEmpty, analysis)
    }
  }
}
