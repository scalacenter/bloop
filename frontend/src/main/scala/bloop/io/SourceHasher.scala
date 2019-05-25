package bloop.io

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedDeque
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileSystems,
  StandardCopyOption,
  FileVisitResult,
  FileVisitOption,
  FileVisitor,
  Files,
  Path,
  SimpleFileVisitor,
  Paths => NioPaths
}

import bloop.data.Project
import bloop.CompilerOracle
import bloop.UniqueCompileInputs
import bloop.engine.ExecutionContext

import monix.reactive.{MulticastStrategy, Consumer, Observable}
import monix.eval.Task

object SourceHasher {
  private final val sourceMatcher = FileSystems.getDefault.getPathMatcher("glob:**.{scala,java}")
  def findAndHashSourcesInProject(
      project: Project,
      parallelUnits: Int
  ): Task[List[UniqueCompileInputs.HashedSource]] = {
    val sourceFilesAndDirectories = project.sources.distinct
    import scala.collection.mutable
    val visitedDirs = new mutable.HashSet[Path]()
    val (observer, observable) = Observable.multicast[Path](
      MulticastStrategy.publish
    )(ExecutionContext.ioScheduler)

    val discovery = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (!sourceMatcher.matches(file)) ()
        else observer.onNext(file)
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        visitedDirs.+=(directory)
        FileVisitResult.CONTINUE
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    val discoverFileTree = Task {
      val opts = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
      sourceFilesAndDirectories.foreach { sourcePath =>
        if (visitedDirs.contains(sourcePath.underlying)) ()
        else Files.walkFileTree(sourcePath.underlying, opts, Int.MaxValue, discovery)
      }
    }.doOnFinish {
      case Some(t) => Task(observer.onError(t))
      case None => Task(observer.onComplete())
    }

    val copyFilesInParallel = observable
      .mapAsync(parallelUnits) { source =>
        Task.eval {
          val hash = ByteHasher.hashFileContents(source.toFile)
          UniqueCompileInputs.HashedSource(AbsolutePath(source), hash)
        }
      }
      .toListL

    Task.mapBoth(discoverFileTree, copyFilesInParallel) {
      case (_, sources) => sources
    }
  }
}
