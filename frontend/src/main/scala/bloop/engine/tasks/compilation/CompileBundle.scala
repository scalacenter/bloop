package bloop.engine.tasks.compilation

import bloop.data.Project
import bloop.engine.Feedback
import bloop.engine.{Dag, ExecutionContext}
import bloop.io.{AbsolutePath, Paths}
import bloop.io.ByteHasher
import bloop.{Compiler, CompilerOracle, ScalaInstance}
import bloop.logging.{Logger, ObservedLogger, LoggerAction}
import bloop.reporter.{ObservedReporter, ReporterAction}
import bloop.tracing.BraveTracer
import bloop.UniqueCompileInputs
import bloop.engine.caches.LastSuccessfulResult

import java.io.File
import java.nio.file.Path

import scala.collection.mutable
import scala.concurrent.Promise

import monix.eval.Task
import monix.reactive.Observable

import xsbti.compile.PreviousResult
import scala.concurrent.ExecutionContext
import bloop.CompileOutPaths

sealed trait CompileBundle

/**
 * Define a compile bundle whose computation was cancelled by the task engine.
 *
 * This value is returned whenever any of the tasks required to compute the
 * successful bundle have been cancelled by the task engine. This value signals
 * the compile scheduler logic that the compilation should be aborted.
 */
case object CancelledCompileBundle extends CompileBundle

/**
 * Define a bundle of high-level information about a project that is going to be
 * compiled. It packs several derived data from the project and makes it
 * available both to the implementation of compile in
 * [[bloop.engine.tasks.CompileTask]] and the logic that runs the compile graph.
 * The latter needs information about Java and Scala sources to appropriately
 * (and efficiently) do build pipelining in mixed Java and Scala setups when
 * enabled.
 *
 * A [[CompileBundle]] has the same [[hashCode()]] and [[equals()]] than
 * [[Project]] for performance reasons. [[CompileBundle]] is a class that is
 * heavily used in the guts of the compilation logic (namely [[CompileGraph]]
 * and [[bloop.engine.tasks.CompileTask]]). Because these classes depend on a
 * fast [[hashCode()]] to cache dags and other instances that contain bundles,
 * our implementation of [[hashCode()]] is as fast as the hash code of a
 * project, which is cached. Using `project`'s hash code does not pose any
 * problem given that the rest of the members of a bundle are derived from a
 * project.
 *
 * @param project The project to compile.
 * @param clientClassesDir The external client-owned classes directory.
 * @param dependenciesData An entity that abstract over all the data of
 * dependent projects, which is required to create a full classpath.
 * @param javaSources A list of Java sources in the project.
 * @param scalaSources A list of Scala sources in the project.
 * @param oracleInputs The compiler oracle inputs are the main input to the
 * compilation task called by [[CompileGraph]].
 * @param cancelCompilation A promise that can be completed to cancel the compilation.
 * @param reporter A reporter instance that will register every reporter action
 * produced by the compilation started by this compile bundle.
 * @param logger A logger instance that will register every logger action
 * produced by the compilation started by this compile bundle.
 * @param mirror An observable that contains all reporter and logger actions.
 * @param lastSuccessful An instance of the last successful result.
 * [[CompileGraph]] will replace the default empty result with the most recent
 * successful result that needs to be used for the compilation.
 * @param latestResult The latest result registered by the client. Required
 * because the reporting of diagnostics might be stateful (BSP diagnostics
 * reporting is, for example) and some of the state is contain in this result.
 */
final case class SuccessfulCompileBundle(
    project: Project,
    clientClassesDir: AbsolutePath,
    dependenciesData: CompileDependenciesData,
    javaSources: List[AbsolutePath],
    scalaSources: List[AbsolutePath],
    uniqueInputs: UniqueCompileInputs,
    cancelCompilation: Promise[Unit],
    reporter: ObservedReporter,
    logger: ObservedLogger[Logger],
    mirror: Observable[Either[ReporterAction, LoggerAction]],
    lastSuccessful: LastSuccessfulResult,
    latestResult: Compiler.Result,
    tracer: BraveTracer
) extends CompileBundle {
  val isJavaOnly: Boolean = scalaSources.isEmpty && !javaSources.isEmpty
  val out: CompileOutPaths = {
    val readOnlyClassesDir = lastSuccessful.classesDir
    CompileOutPaths(
      project.analysisOut,
      project.genericClassesDir,
      clientClassesDir,
      readOnlyClassesDir
    )
  }

  def prepareSourcesAndInstance: Either[ResultBundle, CompileSourcesAndInstance] = {
    import monix.execution.CancelableFuture
    def earlyError(msg: String): ResultBundle =
      ResultBundle(Compiler.Result.GlobalError(msg), None, None)
    def empty: ResultBundle = {
      val last = Some(LastSuccessfulResult.empty(project))
      ResultBundle(Compiler.Result.Empty, last, None)
    }

    val ec = bloop.engine.ExecutionContext.ioScheduler
    val uniqueSources = javaSources ++ scalaSources
    val scalaInstance = project.scalaInstance.orElse(ScalaInstance.scalaInstanceForJavaProjects(logger)(ec))

    scalaInstance match {
      case Some(instance) =>
        (scalaSources, javaSources) match {
          case (Nil, Nil) => Left(empty)
          case (Nil, _ :: _) => Right(CompileSourcesAndInstance(uniqueSources, instance, true))
          case _ => Right(CompileSourcesAndInstance(uniqueSources, instance, false))
        }
      case None =>
        (scalaSources, javaSources) match {
          case (Nil, Nil) => Left(empty)
          case (_: List[AbsolutePath], Nil) =>
            // Let's notify users there is no Scala configuration for a project with Scala sources
            Left(earlyError(Feedback.missingScalaInstance(project)))
          case (_, _: List[AbsolutePath]) =>
            // If Java sources exist, we cannot compile them without an instance, fail fast!
            Left(earlyError(Feedback.missingInstanceForJavaCompilation(project)))
        }
    }
  }

  override val hashCode: Int = project.hashCode
  override def equals(other: Any): Boolean = {
    other match {
      case other: CompileBundle => other.hashCode == this.hashCode
      case _ => false
    }
  }
}

case class CompileSourcesAndInstance(
    sources: List[AbsolutePath],
    instance: ScalaInstance,
    javaOnly: Boolean
)

object CompileBundle {
  implicit val filter = bloop.logging.DebugFilter.Compilation
  def computeFrom(
      inputs: CompileDefinitions.BundleInputs,
      clientExternalClassesDir: AbsolutePath,
      reporter: ObservedReporter,
      lastSuccessful: LastSuccessfulResult,
      lastResult: Compiler.Result,
      cancelCompilation: Promise[Unit],
      logger: ObservedLogger[Logger],
      mirror: Observable[Either[ReporterAction, LoggerAction]],
      tracer: BraveTracer
  ): Task[CompileBundle] = {
    import inputs.{project, dag, dependentProducts}
    tracer.traceTask(s"computing bundle ${project.name}") { tracer =>
      val compileDependenciesData = {
        tracer.trace("dependency classpath") { _ =>
          CompileDependenciesData.compute(
            project.rawClasspath.toArray,
            dependentProducts
          )
        }
      }

      // Dependency classpath is not yet complete, but the hashes only cares about jars
      import bloop.engine.ExecutionContext.ioScheduler
      import compileDependenciesData.dependencyClasspath
      val classpathHashesTask = bloop.io.ClasspathHasher
        .hash(dependencyClasspath, 10, cancelCompilation, ioScheduler, logger, tracer)
        .executeOn(ioScheduler)

      val sourceHashesTask = tracer.traceTask("discovering and hashing sources") { _ =>
        bloop.io.SourceHasher
          .findAndHashSourcesInProject(project, 20, cancelCompilation, ioScheduler)
          .map(res => res.map(_.sortBy(_.source.syntax)))
          .executeOn(ioScheduler)
      }

      logger.debug(s"Computing sources and classpath hashes for ${project.name}")
      Task.mapBoth(classpathHashesTask, sourceHashesTask) {
        case (Left(_), _) => CancelledCompileBundle
        case (_, Left(_)) => CancelledCompileBundle
        case (Right(classpathHashes), Right(sourceHashes)) =>
          val originPath = project.origin.path.syntax
          val originHash = project.origin.hash
          val (javaSources, scalaSources) = {
            import scala.collection.mutable.ListBuffer
            val javaSources = new ListBuffer[AbsolutePath]()
            val scalaSources = new ListBuffer[AbsolutePath]()
            sourceHashes.foreach { hashed =>
              val source = hashed.source
              val sourceName = source.underlying.getFileName().toString
              if (sourceName.endsWith(".scala")) {
                scalaSources += source
              } else if (sourceName.endsWith(".java")) {
                javaSources += source
              } else ()
            }
            javaSources.toList -> scalaSources.toList
          }

          val scalacOptions = project.scalacOptions.toVector
          val scalaJars = project.scalaInstance.toVector.flatMap(_.allJars.map(_.getAbsolutePath()))
          val inputs = UniqueCompileInputs(
            sourceHashes.toVector,
            classpathHashes,
            scalacOptions,
            scalaJars,
            originPath
          )

          new SuccessfulCompileBundle(
            project,
            clientExternalClassesDir,
            compileDependenciesData,
            javaSources,
            scalaSources,
            inputs,
            cancelCompilation,
            reporter,
            logger,
            mirror,
            lastSuccessful,
            lastResult,
            tracer
          )
      }
    }
  }
}
