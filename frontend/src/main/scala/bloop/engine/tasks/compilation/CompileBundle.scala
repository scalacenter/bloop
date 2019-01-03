package bloop.engine.tasks.compilation

import bloop.data.Project
import bloop.engine.Feedback
import bloop.engine.Dag
import bloop.io.{AbsolutePath, Paths}
import bloop.util.ByteHasher
import bloop.{Compiler, ScalaInstance}
import monix.eval.Task
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
 * @param sourceHashes The hashed sources that represent a compile bundle.
 * @param classpathHashes The classpath hashes (with hashes populated only for files, not dirs)
 */
final case class CompileBundle(
    project: Project,
    classpath: Array[AbsolutePath],
    javaSources: List[AbsolutePath],
    scalaSources: List[AbsolutePath],
    sourceHashes: List[CompileBundle.HashedSource],
    classpathHashes: Seq[FileHash]
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
  case class HashedSource(source: AbsolutePath, hash: Int)
  def computeFrom(project: Project, dag: Dag[Project]): Task[CompileBundle] = {
    def hashSources(sources: List[AbsolutePath]): Task[List[HashedSource]] = {
      Task.gather {
        sources.map { source =>
          Task {
            val bytes = java.nio.file.Files.readAllBytes(source.underlying)
            val hash = ByteHasher.hashBytes(bytes)
            HashedSource(source, hash)
          }
        }
      }
    }

    val sources = project.sources.distinct
    val classpath = project.dependencyClasspath(dag)
    val javaSources = sources.flatMap(src => Paths.pathFilesUnder(src, "glob:**.java")).distinct
    val scalaSources = sources.flatMap(src => Paths.pathFilesUnder(src, "glob:**.scala")).distinct
    val allSources = javaSources ++ scalaSources
    val classpath = project.dependencyClasspath(dag)
    val classpathHashesTask = ClasspathHashing.hash(classpath.map(_.toFile))
    val sourceHashesTask = hashSources(allSources.filterNot(_.isDirectory))
    Task.mapBoth(classpathHashesTask, sourceHashesTask) { (classpathHashes, sourceHashes) =>
      new CompileBundle(
        project,
        classpath,
        javaSources,
        scalaSources,
        sourceHashes,
        classpathHashes
      )
    }
  }
}
