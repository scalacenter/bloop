package bloop.engine.tasks.compilation

import bloop.data.Project
import bloop.engine.Feedback
import bloop.engine.{Dag, ExecutionContext}
import bloop.io.{AbsolutePath, Paths}
import bloop.io.ByteHasher
import bloop.{Compiler, CompilerOracle, ScalaInstance, CompileProducts}
import bloop.logging.{Logger, ObservedLogger, LoggerAction}
import bloop.reporter.{ObservedReporter, ReporterAction}
import bloop.tracing.BraveTracer

import java.io.File
import java.nio.file.Path

import scala.collection.mutable

import monix.eval.Task
import monix.reactive.Observable

import xsbti.compile.FileHash
import xsbti.compile.PreviousResult

/**
 * Define a bundle of high-level information about a project that is going to be compiled.
 * It packs several derived data from the project and makes it available both to the
 * implementation of compile in [[bloop.engine.tasks.CompileTask]] and the logic
 * that runs the compile graph. The latter needs information about Java and Scala sources
 * to appropriately (and efficiently) do build pipelining in mixed Java and Scala setups.
 *
 * A [[CompileBundle]] has the same [[hashCode()]] and [[equals()]] than [[Project]]
 * for performance reasons. [[CompileBundle]] is a class that is heavily used in
 * the guts of the compilation logic (namely [[CompileGraph]] and [[bloop.engine.tasks.CompileTask]]).
 * Because these classes depend on a fast [[hashCode()]] to cache dags and other
 * instances that contain bundles, our implementation of [[hashCode()]] is as fast
 * as the hash code of a project, which is cached. Using `project`'s hash code does
 * not pose any problem given that the rest of the members of a bundle are derived
 * from a project.
 *
 * @param project The project we want to compile.
 * @param classpath The full dependency classpath, including resource dirs from dependencies.
 * @param javaSources The found java sources in the file system.
 * @param scalaSources The found scala sources in the file system.
 * @param oracleInputs The compiler oracle inputs that represent a compilation unequivocally.
 */
final case class CompileBundle(
    project: Project,
    dependenciesData: CompileDependenciesData,
    javaSources: List[AbsolutePath],
    scalaSources: List[AbsolutePath],
    oracleInputs: CompilerOracle.Inputs,
    reporter: ObservedReporter,
    logger: ObservedLogger[Logger],
    mirror: Observable[Either[ReporterAction, LoggerAction]]
) {
  val isJavaOnly: Boolean = scalaSources.isEmpty && !javaSources.isEmpty

  def toSourcesAndInstance: Either[Compiler.Result, CompileSourcesAndInstance] = {
    val uniqueSources = javaSources ++ scalaSources
    project.scalaInstance match {
      case Some(instance) =>
        (scalaSources, javaSources) match {
          case (Nil, Nil) => Left(Compiler.Result.Empty)
          case (Nil, _ :: _) => Right(CompileSourcesAndInstance(uniqueSources, instance, true))
          case _ => Right(CompileSourcesAndInstance(uniqueSources, instance, false))
        }
      case None =>
        (scalaSources, javaSources) match {
          case (Nil, Nil) => Left(Compiler.Result.Empty)
          case (_: List[AbsolutePath], Nil) =>
            // Let's notify users there is no Scala configuration for a project with Scala sources
            Left(Compiler.Result.GlobalError(Feedback.missingScalaInstance(project)))
          case (_, _: List[AbsolutePath]) =>
            // If Java sources exist, we cannot compile them without an instance, fail fast!
            Left(Compiler.Result.GlobalError(Feedback.missingInstanceForJavaCompilation(project)))
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
      inputs: CompileGraph.BundleInputs,
      reporter: ObservedReporter,
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
      val classpathHashesTask = bloop.io.ClasspathHasher
        .hash(compileDependenciesData.dependencyClasspath, 10, tracer)
        .executeOn(ExecutionContext.ioScheduler)

      val sourceHashesTask = tracer.traceTask("discovering and hashing sources") { _ =>
        bloop.io.SourceHasher
          .findAndHashSourcesInProject(project, 10)
          .map(_.sortBy(_.source.syntax))
          .executeOn(ExecutionContext.ioScheduler)
      }

      logger.debug(s"Computing sources and classpath hashes for ${project.name}")
      Task.mapBoth(classpathHashesTask, sourceHashesTask) { (classpathHashes, sourceHashes) =>
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
        val inputs = CompilerOracle.Inputs(
          sourceHashes.toVector,
          classpathHashes.toVector,
          originPath,
          originHash
        )
        new CompileBundle(
          project,
          compileDependenciesData,
          javaSources,
          scalaSources,
          inputs,
          reporter,
          logger,
          mirror
        )
      }
    }
  }
}
