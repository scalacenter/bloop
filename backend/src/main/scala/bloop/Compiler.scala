package bloop

import xsbti.compile._
import xsbti.T2
import java.util.Optional
import java.io.File

import bloop.internal.Ecosystem
import bloop.io.AbsolutePath
import bloop.logging.{ObservedLogger, Logger}
import bloop.reporter.{ProblemPerPhase, ZincReporter}
import sbt.internal.inc.bloop.BloopZincCompiler
import sbt.internal.inc.{FreshCompilerCache, InitialChanges, Locate}
import _root_.monix.eval.Task
import bloop.util.{AnalysisUtils, CacheHashCode}
import sbt.internal.inc.bloop.internal.StopPipelining
import sbt.util.InterfaceUtil

import scala.concurrent.Promise

case class CompileInputs(
    scalaInstance: ScalaInstance,
    compilerCache: CompilerCache,
    sources: Array[AbsolutePath],
    classpath: Array[AbsolutePath],
    store: IRStore,
    output: CompileManagedOutput,
    baseDirectory: AbsolutePath,
    scalacOptions: Array[String],
    javacOptions: Array[String],
    compileOrder: CompileOrder,
    classpathOptions: ClasspathOptions,
    previousResult: PreviousResult,
    previousCompilerResult: Compiler.Result,
    reporter: ZincReporter,
    logger: ObservedLogger[Logger],
    mode: CompileMode,
    dependentResults: Map[File, PreviousResult],
    cancelPromise: Promise[Unit]
)

object Compiler {
  private final class ZincClasspathEntryLookup(results: Map[File, PreviousResult])
      extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] = {
      InterfaceUtil.toOptional(results.get(classpathEntry)).flatMap(_.analysis())
    }

    override def definesClass(classpathEntry: File): DefinesClass = {
      Locate.definesClass(classpathEntry)
    }
  }

  private final class BloopProgress(
      reporter: ZincReporter,
      cancelPromise: Promise[Unit]
  ) extends CompileProgress {
    override def startUnit(phase: String, unitPath: String): Unit = {
      reporter.reportNextPhase(phase, new java.io.File(unitPath))
    }

    override def advance(current: Int, total: Int): Boolean = {
      val isNotCancelled = !cancelPromise.isCompleted
      if (isNotCancelled) {
        reporter.reportCompilationProgress(current.toLong, total.toLong)
      }

      isNotCancelled
    }
  }

  sealed trait Result
  object Result {
    final case object Empty extends Result with CacheHashCode
    final case class Blocked(on: List[String]) extends Result with CacheHashCode
    final case class GlobalError(problem: String) extends Result with CacheHashCode

    final case class Success(
        reporter: ZincReporter,
        previous: PreviousResult,
        elapsed: Long
    ) extends Result
        with CacheHashCode

    final case class Failed(
        problems: List[ProblemPerPhase],
        t: Option[Throwable],
        elapsed: Long
    ) extends Result
        with CacheHashCode

    final case class Cancelled(
        problems: List[ProblemPerPhase],
        elapsed: Long
    ) extends Result
        with CacheHashCode

    object Ok {
      def unapply(result: Result): Option[Result] = result match {
        case s @ (Success(_, _, _) | Empty) => Some(s)
        case _ => None
      }
    }

    object NotOk {
      def unapply(result: Result): Option[Result] = result match {
        case f @ (Failed(_, _, _) | Cancelled(_, _) | Blocked(_) | GlobalError(_)) => Some(f)
        case _ => None
      }
    }
  }

  def compile(compileInputs: CompileInputs): Task[Result] = {
    val classesDir = compileInputs.output.uniqueWriteOnlyClassesOut.toFile
    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getCompilationOptions(inputs: CompileInputs): CompileOptions = {
      val sources = inputs.sources // Sources are all files
      val classpath = inputs.classpath.map(_.toFile)

      CompileOptions
        .create()
        .withClassesDirectory(classesDir)
        .withSources(sources.map(_.toFile))
        .withClasspath(classpath)
        .withStore(inputs.store)
        .withScalacOptions(inputs.scalacOptions)
        .withJavacOptions(inputs.javacOptions)
        .withClasspathOptions(inputs.classpathOptions)
        .withOrder(inputs.compileOrder)
    }

    def getClassFileManager(setup: Setup): ClassFileManager = {
      new ClassFileManager {
        import sbt.io.IO
        import scala.collection.JavaConverters._
        private final val resourceSuffixes =
          List(".tasty", ".hasTasty", ".nir", ".hnir", ".sjsir")
        private final val currentOutput = compileInputs.output

        private val deletedClassesAndResourcesObserver =
          currentOutput.deletedClassesAndResourcesObserver

        private val sharedBackupClassesOut =
          currentOutput.sharedBackupClassesOut.toFile
        private val sharedBackupClassesOutPath =
          sharedBackupClassesOut.getCanonicalPath
        private val sharedClassesOut =
          currentOutput.sharedDeleteOnlyClassesOut.toFile
        private val sharedClassesOutPath =
          sharedClassesOut.getCanonicalPath
        private val uniqueWriteOnlyClassesOutPath =
          currentOutput.uniqueWriteOnlyClassesOut.toRealPath().toString

        import java.util.concurrent.ConcurrentHashMap
        val generatedClasses = ConcurrentHashMap.newKeySet[File]()

        /**
         * Deletes a class file and any resource associated with it.
         *
         * The class file/resource can come from either the shared classes
         * directory or the unique write-only classes directory as they are
         * the only ones in the classpath and, in the case of the write-only
         * classes directory, the only one where the compilation products
         * of a given process can be placed to.
         *
         * If the class file/resource are present in the shared classes, we
         * always take a backup and never directly delete it as other processes
         * could be relying on it. However, if the class file/resource are
         * present in the unique write-only classes directory, then they are
         * unique to this compilation process and can be safely removed.
         */
        def delete(classes: Array[File]): Unit = {
          val classPathsToAllResources = classes.flatMap { classFile =>
            val classFilePath = classFile.getCanonicalPath
            if (!classFile.exists || !classFilePath.endsWith(".class")) Nil
            else {
              val removedPrefix = classFile.getAbsolutePath.stripSuffix(".class")
              val additionalFiles = resourceSuffixes
                .map(suffix => new File(removedPrefix + suffix))
                .filter(_.exists)
              List(classFilePath -> (classFile :: additionalFiles))
            }
          }

          classPathsToAllResources.foreach {
            case (classFilePath, allClassResources) =>
              if (classFilePath.startsWith(sharedClassesOutPath)) {
                // If it's in the shared class out, move everything to backup
                allClassResources.foreach { classResource =>
                  val fullResourcePath = classResource.getCanonicalPath
                  val relativePath = fullResourcePath.stripPrefix(sharedClassesOutPath)
                  val newBackupResource = new File(sharedBackupClassesOutPath + relativePath)
                  IO.copyFile(classResource, newBackupResource)
                  deletedClassesAndResourcesObserver.onNext(newBackupResource)
                  IO.delete(classResource)
                }
              } else if (generatedClasses.contains(classFilePath) ||
                         classFilePath.startsWith(uniqueWriteOnlyClassesOutPath)) {
                // The class file is exclusive to this compilation, can be deleted directly
                allClassResources.foreach(classResource => IO.delete(classResource))
              } else {
                // The class file is not in either, this is a fatal error!
                throw new IllegalArgumentException(
                  s"Class file $classFilePath is not neither in $sharedClassesOutPath nor $uniqueWriteOnlyClassesOutPath!"
                )
              }
          }
        }

        def generated(classes: Array[File]): Unit = {
          // Add the canonical files in the generated map for a matter of correctness
          classes.foreach(classFile => generatedClasses.add(classFile.getCanonicalFile))
        }

        def complete(success: Boolean): Unit = {
          if (success) ()
          else {
            // An error ocurred, we can safely remove the write-only contents
            generatedClasses.iterator.asScala.foreach(generatedClass => IO.delete(generatedClass))
            // However, shared dirs will be handled by callers (e.g. `CompileTask` et al)
            ()
          }
        }
      }
    }

    def getSetup(compileInputs: CompileInputs): Setup = {
      val skip = false
      val empty = Array.empty[T2[String, String]]
      val results = compileInputs.dependentResults.+(classesDir -> compileInputs.previousResult)
      val lookup = new ZincClasspathEntryLookup(results)
      val reporter = compileInputs.reporter
      val compilerCache = new FreshCompilerCache
      val cacheFile = compileInputs.baseDirectory.resolve("cache").toFile
      val incOptions = {
        val disableIncremental = java.lang.Boolean.getBoolean("bloop.zinc.disabled")
        val opts = IncOptions.create().withEnabled(!disableIncremental)
        if (!compileInputs.scalaInstance.isDotty) opts
        else Ecosystem.supportDotty(opts)
      }
      val progress =
        Optional.of[CompileProgress](new BloopProgress(reporter, compileInputs.cancelPromise))
      val setup =
        Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
      // We only set the pickle promise here, but the java signal is set in `BloopHighLevelCompiler`
      compileInputs.mode match {
        case p: CompileMode.Pipelined => setup.withIrPromise(p.irs)
        case pp: CompileMode.ParallelAndPipelined => setup.withIrPromise(pp.irs)
        case _ => setup
      }
    }

    val start = System.nanoTime()
    val scalaInstance = compileInputs.scalaInstance
    val classpathOptions = compileInputs.classpathOptions
    val compilers = compileInputs.compilerCache.get(scalaInstance)
    val inputs = getInputs(compilers)
    val manager = getClassFileManager(inputs.setup)

    // We don't need nanosecond granularity, we're happy with milliseconds
    def elapsed: Long = ((System.nanoTime() - start).toDouble / 1e6).toLong

    import ch.epfl.scala.bsp
    import scala.util.{Success, Failure}
    val reporter = compileInputs.reporter
    val logger = compileInputs.logger

    def cancel(): Unit = {
      // Avoid illegal state exception if client cancellation promise is completed
      if (!compileInputs.cancelPromise.isCompleted) {
        compileInputs.cancelPromise.success(())
      }

      // Always report the compilation of a project no matter if it's completed
      reporter.reportCancelledCompilation()
    }

    val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
    val previousSuccessfulProblems =
      previousAnalysis.map(prev => AnalysisUtils.problemsFrom(prev)).getOrElse(Nil)
    val previousProblems: List[ProblemPerPhase] = compileInputs.previousCompilerResult match {
      case f: Compiler.Result.Failed => f.problems
      case c: Compiler.Result.Cancelled => c.problems
      case _: Compiler.Result.Success => previousSuccessfulProblems
      case _ => Nil
    }

    reporter.reportStartCompilation(previousProblems)
    BloopZincCompiler
      .compile(inputs, compileInputs.mode, reporter, ???, logger)
      .materialize
      .doOnCancel(Task(cancel()))
      .map {
        case Success(result) =>
          // Report end of compilation only after we have reported all warnings from previous runs
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Ok)
          val res = PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
          Result.Success(compileInputs.reporter, res, elapsed)
        case Failure(_: xsbti.CompileCancelled) =>
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Cancelled)
          Result.Cancelled(reporter.allProblemsPerPhase.toList, elapsed)
        case Failure(cause) =>
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Error)
          cause match {
            case f: StopPipelining => Result.Blocked(f.failedProjectNames)
            case f: xsbti.CompileFailed =>
              // We cannot assert reporter.problems == f.problems, so we aggregate them together
              val reportedProblems = reporter.allProblemsPerPhase.toList
              val rawProblemsFromReporter = reportedProblems.iterator.map(_.problem).toSet
              val newProblems = f
                .problems()
                .flatMap { p =>
                  if (rawProblemsFromReporter.contains(p)) Nil
                  else List(ProblemPerPhase(p, None))
                }
                .toList
              val failedProblems = reportedProblems ++ newProblems
              Result.Failed(failedProblems, None, elapsed)
            case t: Throwable =>
              t.printStackTrace()
              Result.Failed(Nil, Some(t), elapsed)
          }
      }
  }
}
