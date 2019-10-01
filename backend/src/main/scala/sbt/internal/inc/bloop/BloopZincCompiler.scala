// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop

import java.io.File
import java.util.concurrent.CompletableFuture

import bloop.{CompileMode, CompilerOracle}
import bloop.reporter.ZincReporter
import bloop.logging.ObservedLogger
import bloop.tracing.BraveTracer
import sbt.internal.inc.bloop.internal.{BloopStamps, BloopLookup}

import monix.eval.Task
import sbt.internal.inc.{Analysis, CompileConfiguration, CompileOutput, Incremental, MiniSetupUtil, MixedAnalyzingCompiler}
import xsbti.{AnalysisCallback, Logger}
import sbt.internal.inc.JavaInterfaceUtil.{EnrichOptional, EnrichSbtTuple}
import sbt.internal.inc.bloop.internal.{BloopHighLevelCompiler, BloopIncremental}
import sbt.util.InterfaceUtil
import xsbti.compile._
import sbt.internal.inc.JarUtils
import bloop.UniqueCompileInputs
import scala.concurrent.Promise

object BloopZincCompiler {
  import bloop.logging.DebugFilter
  private implicit val filter = DebugFilter.Compilation

  /**
   * Performs an incremental compilation based on [[xsbti.compile.Inputs]].
   *
   * This is a Scala implementation of [[xsbti.compile.IncrementalCompiler]],
   * check the docs for more information on the specification of this method.
   *
   * @param in An instance of [[xsbti.compile.Inputs]] that collect all the
   *           inputs required to run the compiler (from sources and classpath,
   *           to compilation order, previous results, current setup, etc).
   * @param compileMode The compiler mode in which compilation needs to run.
   * @param logger An instance of [[xsbti.Logger]] to log Zinc output.
   *
   * @return An instance of [[xsbti.compile.CompileResult]] that holds
   *         information about the results of the compilation. The returned
   *         [[xsbti.compile.CompileResult]] must be used for subsequent
   *         compilations that depend on the same inputs, check its api and its
   *         field [[xsbti.compile.CompileAnalysis]].
   */
  def compile(
      in: Inputs,
      compileMode: CompileMode,
      reporter: ZincReporter,
      logger: ObservedLogger[_],
      uniqueInputs: UniqueCompileInputs,
      manager: ClassFileManager,
      cancelPromise: Promise[Unit],
      tracer: BraveTracer
  ): Task[CompileResult] = {
    val config = in.options()
    val setup = in.setup()
    import config._
    import setup.{reporter => _, _}
    val compilers = in.compilers
    val javacChosen = compilers.javaTools.javac
    val scalac = compilers.scalac
    val extraOptions = extra.toList.map(_.toScalaTuple)
    tracer.traceTask("zinc entrypoint") { tracer =>
      compileIncrementally(
        scalac,
        javacChosen,
        sources,
        classpath,
        uniqueInputs,
        CompileOutput(classesDirectory),
        cache,
        progress().toOption,
        scalacOptions,
        javacOptions,
        classpathOptions,
        in.previousResult.analysis.toOption,
        in.previousResult.setup.toOption,
        perClasspathEntryLookup,
        reporter,
        order,
        skip,
        incrementalCompilerOptions,
        extraOptions,
        compileMode,
        manager,
        cancelPromise,
        tracer
      )(logger)
    }
  }

  def compileIncrementally(
      scalaCompiler: xsbti.compile.ScalaCompiler,
      javaCompiler: xsbti.compile.JavaCompiler,
      sources: Array[File],
      classpath: Seq[File],
      uniqueInputs: UniqueCompileInputs,
      output: Output,
      cache: GlobalsCache,
      progress: Option[CompileProgress] = None,
      scalaOptions: Seq[String] = Nil,
      javaOptions: Seq[String] = Nil,
      classpathOptions: ClasspathOptions,
      previousAnalysis: Option[CompileAnalysis],
      previousSetup: Option[MiniSetup],
      perClasspathEntryLookup: PerClasspathEntryLookup,
      reporter: ZincReporter,
      compileOrder: CompileOrder = CompileOrder.Mixed,
      skip: Boolean = false,
      incrementalOptions: IncOptions,
      extra: List[(String, String)],
      compileMode: CompileMode,
      manager: ClassFileManager,
      cancelPromise: Promise[Unit],
      tracer: BraveTracer
  )(implicit logger: ObservedLogger[_]): Task[CompileResult] = {
    val prev = previousAnalysis match {
      case Some(previous) => previous
      case None => Analysis.empty
    }

    // format: off
    val configTask = configureAnalyzingCompiler(scalaCompiler, javaCompiler, sources.toSeq, classpath, uniqueInputs.classpath, output, cache, progress, scalaOptions, javaOptions, classpathOptions, prev, previousSetup, perClasspathEntryLookup, reporter, compileOrder, skip, incrementalOptions, extra, tracer)
    // format: on
    configTask.flatMap { config =>
      if (skip) Task.now(CompileResult.of(prev, config.currentSetup, false))
      else {
        val setOfSources = sources.toSet
        val compiler = BloopHighLevelCompiler(config, reporter, logger, tracer)
        val lookup = new BloopLookup(config, previousSetup, logger)
        val analysis = invalidateAnalysisFromSetup(config.currentSetup, previousSetup, incrementalOptions.ignoredScalacOptions(), setOfSources, prev, manager, logger)

        // Scala needs the explicit type signature to infer the function type arguments
        val compile: (Set[File], DependencyChanges, AnalysisCallback, ClassFileManager) => Task[Unit] = compiler.compile(_, _, _, _, compileMode, cancelPromise)
        BloopIncremental.compile(setOfSources, uniqueInputs, lookup, compile, analysis, output, logger, reporter, config.incOptions, compileMode, manager, tracer).map {
          case (changed, analysis) => CompileResult.of(analysis, config.currentSetup, changed)
        }
      }
    }
  }

  /**
   * Invalidates the analysis file to be used depending if the setup is the same or not.
   *
   * This logic used to be in `compileInternal` in
   * [[sbt.internal.inc.IncrementalCompilerImpl.compileInternal]], but we've moved it here
   * to reason more accurately about it.
   *
   * @param setup The current setup that we use to compile.
   * @param previousSetup The previous setup, [[None]] if first compilation.
   * @param ignoredScalacOptions The scalac options we should ignore for equivalence.
   * @param sources The sources with which we prune the analysis file.
   * @param previousAnalysis The analysis from the previous compilation.
   * @return An analysis, either empty if the setups are not the same, pruned or the previous analysis.
   */
  def invalidateAnalysisFromSetup(
      setup: MiniSetup,
      previousSetup: Option[MiniSetup],
      ignoredScalacOptions: Array[String],
      sources: Set[File],
      previousAnalysis: CompileAnalysis,
      manager: ClassFileManager,
      logger: ObservedLogger[_]
  ): CompileAnalysis = {
    // Copied from `Incremental` to pass in the class file manager we want
    def prune(invalidatedSrcs: Set[File], previous0: CompileAnalysis, classfileManager: ClassFileManager): Analysis = {
      val previous = previous0 match { case a: Analysis => a }
      classfileManager.delete(invalidatedSrcs.flatMap(previous.relations.products).toArray)
      previous -- invalidatedSrcs
    }

    // Need wildcard import b/c otherwise `scala.math.Equiv.universal` is used and returns false
    import MiniSetupUtil._

    /* Define first because `Equiv[CompileOrder.value]` dominates `Equiv[MiniSetup]`. */
    import xsbti.compile.Output
    val equiv: Equiv[MiniSetup] = {
      new Equiv[MiniSetup] {
        def equiv(a: MiniSetup, b: MiniSetup) = {
          /* Hard-code these to use the `Equiv` defined in this class. For
           * some reason, `Equiv[Nothing]` or an equivalent is getting injected
           * into here now, and it's borking all our results. This fixes it. */
          //def sameOutput = MiniSetupUtil.equivOutput.equiv(a.output, b.output)
          def sameOptions = MiniSetupUtil.equivOpts.equiv(a.options, b.options)
          def sameCompiler = MiniSetupUtil.equivCompilerVersion.equiv(a.compilerVersion, b.compilerVersion)
          def sameOrder = a.order == b.order
          def sameExtra = MiniSetupUtil.equivPairs.equiv(a.extra, b.extra)

          // Don't compare outputs because bloop changes them across compiler runs
          //sameOutput &&
          sameOptions &&
          sameCompiler &&
          sameOrder &&
          sameExtra
        }
      }
    }

    previousSetup match {
      case Some(previous) => // Return an empty analysis if values of extra have changed
        if (equiv.equiv(previous, setup)) previousAnalysis
        else if (!equivPairs.equiv(previous.extra, setup.extra)) Analysis.empty
        else prune(sources, previousAnalysis, manager)
      case None =>
        logger.debug("No previous setup found, invalidating everything.")
        prune(sources, previousAnalysis, manager)
    }
  }

  def configureAnalyzingCompiler(
      scalac: xsbti.compile.ScalaCompiler,
      javac: xsbti.compile.JavaCompiler,
      sources: Seq[File],
      classpath: Seq[File],
      classpathHashes: Seq[FileHash],
      output: Output,
      cache: GlobalsCache,
      progress: Option[CompileProgress] = None,
      options: Seq[String] = Nil,
      javacOptions: Seq[String] = Nil,
      classpathOptions: ClasspathOptions,
      previousAnalysis: CompileAnalysis,
      previousSetup: Option[MiniSetup],
      perClasspathEntryLookup: PerClasspathEntryLookup,
      reporter: ZincReporter,
      compileOrder: CompileOrder = CompileOrder.Mixed,
      skip: Boolean = false,
      incrementalCompilerOptions: IncOptions,
      extra: List[(String, String)],
      tracer: BraveTracer
  ): Task[CompileConfiguration] = Task.now {
    // Remove directories from classpath hashes, we're only interested in jars
    val jarClasspathHashes = BloopLookup.filterOutDirsFromHashedClasspath(classpathHashes)
    val compileSetup = MiniSetup.of(
      output,
      MiniOptions.of(
        jarClasspathHashes.toArray,
        options.toArray,
        javacOptions.toArray
      ),
      scalac.scalaInstance.actualVersion,
      compileOrder,
      incrementalCompilerOptions.storeApis(),
      (extra map InterfaceUtil.t2).toArray
    )

    val outputJar = JarUtils.createOutputJarContent(output)
    MixedAnalyzingCompiler.config(
      sources,
      classpath,
      classpathOptions,
      compileSetup,
      progress,
      previousAnalysis,
      previousSetup,
      perClasspathEntryLookup,
      scalac,
      javac,
      reporter,
      skip,
      cache,
      incrementalCompilerOptions,
      outputJar
    )
  }
}
