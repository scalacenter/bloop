package bloop.engine.tasks.compilation

import bloop.data.Project
import bloop.engine.Feedback
import bloop.engine.{Dag, ExecutionContext}
import bloop.io.{AbsolutePath, Paths}
import bloop.util.ByteHasher
import bloop.{Compiler, CompilerOracle, ScalaInstance}
import bloop.logging.{Logger, ObservedLogger, LoggerAction}
import bloop.reporter.{ObservedReporter, ReporterAction}

import monix.eval.Task
import monix.reactive.Observable
import sbt.internal.inc.bloop.ClasspathHashing
import xsbti.compile.FileHash

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
    classpath: Array[AbsolutePath],
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
  def computeFrom(
      project: Project,
      dag: Dag[Project],
      reporter: ObservedReporter,
      logger: ObservedLogger[Logger],
      mirror: Observable[Either[ReporterAction, LoggerAction]]
  ): Task[CompileBundle] = {
    def hashSources(sources: List[AbsolutePath]): Task[List[CompilerOracle.HashedSource]] = {
      Task.gather {
        sources.map { source =>
          Task {
            val bytes = java.nio.file.Files.readAllBytes(source.underlying)
            val hash = ByteHasher.hashBytes(bytes)
            CompilerOracle.HashedSource(source, hash)
          }
        }
      }
    }

    val sources = project.sources.distinct
    val classpath = project.dependencyClasspath(dag)
    val classpathHashesTask = ClasspathHashing.hash(classpath.map(_.toFile))
    val javaSources = sources.flatMap(src => Paths.pathFilesUnder(src, "glob:**.java")).distinct
    val scalaSources = sources.flatMap(src => Paths.pathFilesUnder(src, "glob:**.scala")).distinct
    val allSources = javaSources ++ scalaSources
    val sourceHashesTask = hashSources(allSources.filterNot(_.isDirectory))
    Task.mapBoth(classpathHashesTask, sourceHashesTask) { (classpathHashes, sourceHashes) =>
      val originPath = project.origin.path.syntax
      val originHash = project.origin.hash
      val inputs = CompilerOracle.Inputs(sourceHashes, classpathHashes, originPath, originHash)
      new CompileBundle(
        project,
        classpath,
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
