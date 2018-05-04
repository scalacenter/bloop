// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop

import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture

import monix.eval.Task
import sbt.internal.inc.{Analysis, CompileOutput, Incremental, LookupImpl, MiniSetupUtil, MixedAnalyzingCompiler}
import xsbti.{AnalysisCallback, Logger, Reporter}
import sbt.internal.inc.JavaInterfaceUtil.{EnrichOptional, EnrichSbtTuple}
import sbt.internal.inc.bloop.internal.{BloopHighLevelCompiler, BloopIncremental}
import xsbti.compile._

object BloopZincCompiler {

  /**
   * Performs an incremental compilation based on [[xsbti.compile.Inputs]].
   *
   * This is a Scala implementation of [[xsbti.compile.IncrementalCompiler]],
   * check the docs for more information on the specification of this method.
   *
   * @param in An instance of [[xsbti.compile.Inputs]] that collect all the
   *           inputs required to run the compiler (from sources and classpath,
   *           to compilation order, previous results, current setup, etc).
   * @param startJavaCompilation An task that completes whenever java can start
   *                            to compile.
   * @param logger An instance of [[xsbti.Logger]] to log Zinc output.
   *
   * @return An instance of [[xsbti.compile.CompileResult]] that holds
   *         information about the results of the compilation. The returned
   *         [[xsbti.compile.CompileResult]] must be used for subsequent
   *         compilations that depend on the same inputs, check its api and its
   *         field [[xsbti.compile.CompileAnalysis]].
   */
  def compile(in: Inputs, startJavaCompilation: Task[Boolean], logger: Logger): Task[CompileResult] = {
    val config = in.options()
    val setup = in.setup()
    import config._
    import setup._
    val compilers = in.compilers
    val javacChosen = compilers.javaTools.javac
    val scalac = compilers.scalac
    val extraOptions = extra.toList.map(_.toScalaTuple)
    compileIncrementally(
      scalac,
      javacChosen,
      sources,
      classpath,
      picklepath,
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
      picklePromise.toOption,
      startJavaCompilation
    )(logger)
  }

  def compileIncrementally(
      scalaCompiler: xsbti.compile.ScalaCompiler,
      javaCompiler: xsbti.compile.JavaCompiler,
      sources: Array[File],
      classpath: Seq[File],
      picklepath: Seq[URI],
      output: Output,
      cache: GlobalsCache,
      progress: Option[CompileProgress] = None,
      scalaOptions: Seq[String] = Nil,
      javaOptions: Seq[String] = Nil,
      classpathOptions: ClasspathOptions,
      previousAnalysis: Option[CompileAnalysis],
      previousSetup: Option[MiniSetup],
      perClasspathEntryLookup: PerClasspathEntryLookup,
      reporter: Reporter,
      compileOrder: CompileOrder = CompileOrder.Mixed,
      skip: Boolean = false,
      incrementalOptions: IncOptions,
      extra: List[(String, String)],
      picklePromise: Option[CompletableFuture[URI]],
      startJavaCompilation: Task[Boolean]
  )(implicit logger: Logger): Task[CompileResult] = {
    val prev = previousAnalysis match {
      case Some(previous) => previous
      case None => Analysis.empty
    }

    // format: off
    val configTask = Task.eval(MixedAnalyzingCompiler.makeConfig(scalaCompiler, javaCompiler, sources.toSeq, classpath, picklepath, output, cache, progress, scalaOptions, javaOptions, classpathOptions, prev, previousSetup, perClasspathEntryLookup, reporter, compileOrder, skip, incrementalOptions, extra))
    // format: on
    configTask.flatMap { config =>
      if (skip) Task.now(CompileResult.of(prev, config.currentSetup, false))
      else {
        val setup = config.currentSetup
        val compiler = BloopHighLevelCompiler(config, logger)
        val equiv = MiniSetupUtil.equivCompileSetup
        val equivPairs = MiniSetupUtil.equivPairs
        val lookup = new LookupImpl(config, previousSetup)
        val srcsSet = sources.toSet
        val analysis = previousSetup match {
          case Some(previous) => // Return an empty analysis if values of extra have changed
            if (equiv.equiv(previous, setup)) prev
            else if (!equivPairs.equiv(previous.extra, setup.extra)) Analysis.empty
            else Incremental.prune(srcsSet, prev)
          case None => Incremental.prune(srcsSet, prev)
        }

        // Scala needs the explicit type signature to infer the function type arguments
        val compile: (Set[File], DependencyChanges, AnalysisCallback, ClassFileManager) => Task[Unit] = compiler.compile(_, _, _, _, startJavaCompilation)
        BloopIncremental.compile(srcsSet, lookup, compile, analysis, output, logger, config.incOptions, picklePromise).map {
          case (changed, analysis) => CompileResult.of(analysis, config.currentSetup, changed)
        }
      }
    }
  }
}
