package bloop.engine.tasks

import bloop.{CompileInputs, CompileMode, Compiler, CompileOutPaths, CompileProducts}
import bloop.cli.ExitStatus
import bloop.data.Project
import bloop.engine.{State, Dag, ExecutionContext, Feedback}
import bloop.engine.caches.{ResultsCache, LastSuccessfulResult}
import bloop.engine.tasks.compilation.{FinalCompileResult, _}
import bloop.io.{AbsolutePath, ParallelOps}
import bloop.io.ParallelOps.CopyMode
import bloop.tracing.BraveTracer
import bloop.logging.{BspServerLogger, DebugFilter, Logger, ObservedLogger, LoggerAction}
import bloop.reporter.{
  Reporter,
  ReporterAction,
  ReporterConfig,
  ReporterInputs,
  LogReporter,
  ObservedReporter
}

import java.util.UUID
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

import monix.eval.Task
import monix.execution.CancelableFuture
import monix.reactive.{Observer, Observable, MulticastStrategy}

import sbt.internal.inc.AnalyzingCompiler
import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup}

import scala.concurrent.Promise

object CompileTask {
  private implicit val logContext: DebugFilter = DebugFilter.Compilation
  def compile[UseSiteLogger <: Logger](
      state: State,
      dag: Dag[Project],
      createReporter: ReporterInputs[UseSiteLogger] => Reporter,
      userCompileMode: CompileMode.ConfigurableMode,
      pipeline: Boolean,
      excludeRoot: Boolean,
      cancelCompilation: Promise[Unit],
      rawLogger: UseSiteLogger
  ): Task[State] = {
    import bloop.internal.build.BuildInfo
    val originUri = state.build.origin
    val cwd = originUri.getParent
    val topLevelTargets = Dag.directDependencies(List(dag)).mkString(", ")
    val rootTracer = BraveTracer(
      s"compile $topLevelTargets (transitively)",
      "bloop.version" -> BuildInfo.version,
      "zinc.version" -> BuildInfo.zincVersion,
      "build.uri" -> originUri.syntax,
      "compile.target" -> topLevelTargets
    )

    val bgTracer = rootTracer.toIndependentTracer(
      s"background IO work after compiling $topLevelTargets (transitively)",
      "bloop.version" -> BuildInfo.version,
      "zinc.version" -> BuildInfo.zincVersion,
      "build.uri" -> originUri.syntax,
      "compile.target" -> topLevelTargets
    )

    def compile(graphInputs: CompileGraph.Inputs): Task[ResultBundle] = {
      val bundle = graphInputs.bundle
      val project = bundle.project
      val logger = bundle.logger
      val reporter = bundle.reporter
      val previousResult = bundle.latestResult
      val lastSuccessful = bundle.lastSuccessful
      val compileProjectTracer = rootTracer.startNewChildTracer(
        s"compile ${project.name}",
        "compile.target" -> project.name
      )

      bundle.toSourcesAndInstance match {
        case Left(earlyResult) =>
          val complete = CompileExceptions.CompletePromise(graphInputs.store)
          graphInputs.irPromise.completeExceptionally(complete)
          graphInputs.completeJava.complete(())
          compileProjectTracer.terminate()
          Task.now(ResultBundle(earlyResult, None))
        case Right(CompileSourcesAndInstance(sources, instance, javaOnly)) =>
          val readOnlyClassesDir = lastSuccessful.classesDir
          val externalUserClassesDir = state.client.getUniqueClassesDirFor(project)
          val compileOut = CompileOutPaths(
            project.analysisOut,
            externalUserClassesDir,
            readOnlyClassesDir
          )

          val newClassesDir = compileOut.internalNewClassesDir
          val classpath = bundle.dependenciesData.buildFullCompileClasspathFor(
            project,
            readOnlyClassesDir,
            newClassesDir
          )

          // Warn user if detected missing dep, see https://github.com/scalacenter/bloop/issues/708
          state.build.hasMissingDependencies(project).foreach { missing =>
            Feedback
              .detectMissingDependencies(project.name, missing)
              .foreach(msg => logger.warn(msg))
          }

          val configuration = configureCompilation(project, pipeline, graphInputs, userCompileMode)
          val newScalacOptions = {
            CompilerPluginWhitelist
              .enableCachingInScalacOptions(
                instance.version,
                configuration.scalacOptions,
                logger,
                compileProjectTracer,
                5
              )
          }

          val inputs = newScalacOptions.map { newScalacOptions =>
            CompileInputs(
              instance,
              state.compilerCache,
              sources.toArray,
              classpath,
              bundle.oracleInputs,
              graphInputs.store,
              compileOut,
              project.out,
              newScalacOptions.toArray,
              project.javacOptions.toArray,
              project.compileOrder,
              project.classpathOptions,
              lastSuccessful.previous,
              previousResult,
              reporter,
              logger,
              configuration.mode,
              graphInputs.dependentResults,
              cancelCompilation,
              compileProjectTracer,
              ExecutionContext.ioScheduler,
              ExecutionContext.ioExecutor,
              bundle.dependenciesData.allInvalidatedClassFiles
            )
          }

          val setUpEnvironment = rootTracer.traceTask("wait to populate classes dir") { _ =>
            // This task is memoized and started by the compilation that created
            // it, so this execution blocks until it's run or completes right away
            lastSuccessful.populatingProducts
          }

          // Block on the task associated with this result that sets up the read-only classes dir
          setUpEnvironment.flatMap { _ =>
            // Only when the task is finished, we kickstart the compilation
            inputs.flatMap(inputs => Compiler.compile(inputs)).map { result =>
              // Finish incomplete promises (out of safety) and run similar book-keeping
              runPipeliningBookkeeping(graphInputs, result, pipeline, javaOnly, logger)
              // Finish the tracer as soon as possible
              compileProjectTracer.terminate()

              // Populate the last successful result if result was success
              result match {
                case s: Compiler.Result.Success =>
                  // Don't start populating tasks until background tasks are done
                  val populatingTask = {
                    if (s.isNoOp) Task.fromFuture(s.backgroundTasks)
                    else {
                      Task.fromFuture(s.backgroundTasks).flatMap { _ =>
                        createNewReadOnlyClassesDir(s.products, bgTracer, rawLogger).map(_ => ())
                      }
                    }
                  }.memoize

                  // Memoize so that no matter how many times it's run, only once it's executed
                  val last = LastSuccessfulResult(bundle.oracleInputs, s.products, populatingTask)
                  ResultBundle(s, Some(last))
                case result => ResultBundle(result, None)
              }
            }
          }
      }
    }

    def setup(inputs: CompileGraph.BundleInputs): Task[CompileBundle] = {
      // Create a multicast observable stream to allow multiple mirrors of loggers
      val (observer, obs) = {
        Observable.multicast[Either[ReporterAction, LoggerAction]](
          MulticastStrategy.replay
        )(ExecutionContext.ioScheduler)
      }

      // Compute the previous and last successful results from the results cache
      val (prev, last) = {
        import inputs.project
        // The last successful result is picked in [[CompileGraph]], doesn't
        // come from the results cache, which is only used to populate result
        val clientLastSuccessful = LastSuccessfulResult.empty(project)
        if (pipeline) {
          // Disable incremental compilation if pipelining is enabled
          Compiler.Result.Empty -> clientLastSuccessful
        } else {
          val latestResult = state.results.latestResult(project)
          latestResult -> clientLastSuccessful
        }
      }

      val logger = ObservedLogger(rawLogger, observer)
      val underlying = createReporter(ReporterInputs(inputs.project, cwd, rawLogger))
      val reporter = new ObservedReporter(logger, underlying)
      CompileBundle.computeFrom(inputs, reporter, last, prev, logger, obs, rootTracer)
    }

    val client = state.client
    CompileGraph.traverse(dag, client, setup(_), compile(_), pipeline).flatMap { partialDag =>
      val partialResults = Dag.dfs(partialDag)
      val finalResults = partialResults.map(r => PartialCompileResult.toFinalResult(r))
      Task.gatherUnordered(finalResults).map(_.flatten).flatMap { results =>
        results.foreach { finalResult =>
          /*
           * Iterate through every final compile result at the end of the
           * compilation run and trigger a background task to populate the new
           * read-only classes directory. This task can also be triggered by
           * other concurrent processes needing to use this result, but we
           * trigger it now to use the gaps between compiler requests more
           * effectively. It's likely we will not have another compile request
           * requiring this last successful result immediately, so we start the
           * task now to spare some time the next time a compilation request
           * comes in. Note the task is memoized internally..
           */
          finalResult.result.successful
            .foreach(l => l.populatingProducts.runAsync(ExecutionContext.ioScheduler))
        }

        cleanStatePerBuildRun(dag, results, state)
        val stateWithResults = state.copy(results = state.results.addFinalResults(results))
        val failures = results.flatMap {
          case FinalNormalCompileResult(p, results, _) =>
            results.fromCompiler match {
              case Compiler.Result.NotOk(_) => List(p)
              case _ => Nil
            }
          case _ => Nil
        }

        val newState: State = {
          if (failures.isEmpty) {
            stateWithResults.copy(status = ExitStatus.Ok)
          } else {
            results.foreach {
              case FinalNormalCompileResult.HasException(project, t) =>
                rawLogger
                  .error(s"Unexpected error when compiling ${project.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                t.printStackTrace()
                rawLogger.trace(t)
              case _ => () // Do nothing when the final compilation result is not an actual error
            }

            failures.foreach(p => rawLogger.error(s"'${p.name}' failed to compile."))
            stateWithResults.copy(status = ExitStatus.CompilationError)
          }
        }

        val backgroundTasks = Task.gatherUnordered {
          results.flatMap {
            case FinalNormalCompileResult(_, results: ResultBundle, _) =>
              results.fromCompiler match {
                case s: Compiler.Result.Success => List(Task.fromFuture(s.backgroundTasks))
                case f: Compiler.Result.Failed => List(Task.fromFuture(f.backgroundTasks))
                case c: Compiler.Result.Cancelled => List(Task.fromFuture(c.backgroundTasks))
                case _ => Nil
              }
            case _ => Nil
          }
        }

        // Block on all background task operations to fully populate classes directories
        backgroundTasks.map { _ =>
          // Terminate the tracer after we've blocked on the background tasks
          rootTracer.terminate()
          newState
        }
      }
    }
  }

  // Running this method takes around 10ms per compiler and it's rare to have more than one to reset!
  private def cleanStatePerBuildRun(
      dag: Dag[Project],
      results: List[FinalCompileResult],
      state: State
  ): Unit = {
    import xsbti.compile.{IRStore, EmptyIRStore}
    val tmpDir = Files.createTempDirectory("outputDir")
    try {
      // Merge IRs of all stores (no matter if they were generated by different Scala instances)
      val mergedStore = results.foldLeft(EmptyIRStore.getStore: IRStore) {
        case (acc, result) => acc.merge(result.store)
      }

      val instances = results.iterator.flatMap {
        case FinalNormalCompileResult(project, _, _) => project.scalaInstance
        case FinalEmptyResult => Nil
      }.toSet

      instances.foreach { i =>
        // Initialize a compiler so that we can reset the global state after a build compilation
        val scalac = state.compilerCache.get(i).scalac().asInstanceOf[AnalyzingCompiler]
        val config = ReporterConfig.defaultFormat
        val cwd = state.commonOptions.workingPath
        val randomProjectFromDag = Dag.dfs(dag).head
        val logger = ObservedLogger.dummy(state.logger, ExecutionContext.ioScheduler)
        val reporter = new LogReporter(randomProjectFromDag, logger, cwd, config)
        val output = new sbt.internal.inc.ConcreteSingleOutput(tmpDir.toFile)
        val cached = scalac.newCachedCompiler(Array.empty[String], output, logger, reporter)
        // Reset the global ir caches on the cached compiler only for the store IRs
        scalac.resetGlobalIRCaches(mergedStore, cached, logger)
      }
    } finally {
      Files.delete(tmpDir)
    }
  }

  private final val GeneratePicklesFlag = "-Ygenerate-pickles"
  case class ConfiguredCompilation(mode: CompileMode, scalacOptions: List[String])
  private def configureCompilation(
      project: Project,
      pipeline: Boolean,
      graphInputs: CompileGraph.Inputs,
      configurableMode: CompileMode.ConfigurableMode
  ): ConfiguredCompilation = {
    if (!pipeline) ConfiguredCompilation(configurableMode, project.scalacOptions)
    else {
      val scalacOptions = (GeneratePicklesFlag :: project.scalacOptions)
      val newMode = configurableMode match {
        case CompileMode.Sequential =>
          CompileMode.Pipelined(
            graphInputs.irPromise,
            graphInputs.completeJava,
            graphInputs.transitiveJavaSignal,
            graphInputs.oracle,
            graphInputs.separateJavaAndScala
          )
        case CompileMode.Parallel(batches) =>
          CompileMode.ParallelAndPipelined(
            batches,
            graphInputs.irPromise,
            graphInputs.completeJava,
            graphInputs.transitiveJavaSignal,
            graphInputs.oracle,
            graphInputs.separateJavaAndScala
          )
      }
      ConfiguredCompilation(newMode, scalacOptions)
    }
  }

  private def runPipeliningBookkeeping(
      inputs: CompileGraph.Inputs,
      result: Compiler.Result,
      pipeline: Boolean,
      javaOnly: Boolean,
      logger: Logger
  ): Unit = {
    val projectName = inputs.bundle.project.name

    if (!inputs.irPromise.isDone) {
      // Avoid deadlocks in case pipelining is disabled in the Zinc bridge
      result match {
        case Compiler.Result.NotOk(_) =>
          inputs.irPromise.completeExceptionally(CompileExceptions.FailPromise)
        case result =>
          if (pipeline && !javaOnly) {
            logger.warn(s"The project $projectName didn't use pipelined compilation.")
          }

          val complete = CompileExceptions.CompletePromise(inputs.store)
          inputs.irPromise.completeExceptionally(complete)
      }
    } else {
      // Report if the pickle ready was correctly completed by the compiler
      inputs.irPromise.get match {
        case Array() if pipeline =>
          logger.warn(s"Project $projectName compiled without pipelined compilation.")
        case _ => logger.debug(s"The pickle promise of $projectName completed in Zinc.")
      }
    }

    ()
  }

  private def createNewReadOnlyClassesDir(
      products: CompileProducts,
      tracer: BraveTracer,
      logger: Logger
  ): Task[Unit] = {
    // Do nothing if origin and target classes dir are the same, as protective measure
    if (products.readOnlyClassesDir == products.newClassesDir) {
      logger.warn(s"Running `createNewReadOnlyClassesDir` on same dir ${products.newClassesDir}")
      Task.now(())
    } else {
      // Blacklist ensure final dir doesn't contain class files that don't map to source files
      val blacklist = products.invalidatedClassFiles.iterator.map(_.toPath).toSet
      val config = ParallelOps.CopyConfiguration(5, CopyMode.NoReplace, blacklist)
      val task = tracer.traceTask("preparing new read-only classes directory") { _ =>
        ParallelOps.copyDirectories(config)(
          products.readOnlyClassesDir,
          products.newClassesDir,
          ExecutionContext.ioScheduler,
          logger
        )
      }

      task.map(rs => ()).memoize
    }
  }
}
