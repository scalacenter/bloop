package bloop.io

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable
import scala.concurrent.Promise

import bloop.UniqueCompileInputs.HashedSource
import bloop.data.Project
import bloop.engine.SourceGenerator
import bloop.task.Task
import bloop.util.monix.FoldLeftAsyncConsumer

import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean
import monix.reactive.Consumer
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import sbt.internal.inc.PlainVirtualFileConverter

object SourceHasher {
  private final val sourceMatcher =
    FileSystems.getDefault.getPathMatcher("glob:**/[!.]*.{scala,java}")

  /**
   * Matches a Scala or Java source file that is not a hidden file (e.g.
   * doesn't start with a leading dot.)
   *
   * @param path is the path of the source file candidate.
   * @return whether the path can be considered a valid source file.
   */
  def matchSourceFile(path: Path): Boolean = {
    sourceMatcher.matches(path)
  }

  /**
   * Find sources in a project and hash them in parallel.
   *
   * NOTE: When the task returned by this method is cancelled, the promise
   * `cancelCompilation` will be completed.
   *
   * @param project The project where the sources will be discovered.
   * @param updateGenerator A function that runs the given source generator.
   * @param parallelUnits The amount of sources we can hash at once.
   * @param cancelCompilation A promise that will be completed if task is cancelled.
   * @param scheduler The scheduler that should be used for internal Monix usage.
   */
  def findAndHashSourcesInProject(
      project: Project,
      updateGenerator: SourceGenerator => Task[List[AbsolutePath]],
      parallelUnits: Int,
      cancelCompilation: Promise[Unit],
      scheduler: Scheduler
  ): Task[Either[Unit, List[HashedSource]]] = {
    val isCancelled = AtomicBoolean(false)
    val sourceFilesAndDirectories = project.sources.distinct
    val visitedDirs = new mutable.HashSet[Path]()
    val (observer, observable) = Observable.multicast[Path](MulticastStrategy.publish)(scheduler)

    val subscribed = Promise[Unit]()
    def fileVisitor(matches: Path => Boolean) = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (isCancelled.get) FileVisitResult.TERMINATE
        else {
          // `visitFile` can be called on a directory if we reach the max depth.
          if (attributes.isDirectory || !matches(file)) ()
          else observer.onNext(file)
          FileVisitResult.CONTINUE
        }
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        if (isCancelled.get) FileVisitResult.TERMINATE
        else {
          visitedDirs.+=(directory)
          FileVisitResult.CONTINUE
        }
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    val discoverUnmanagedFileTree = Task {
      val discovery = fileVisitor(matchSourceFile)
      val opts = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
      sourceFilesAndDirectories.foreach { sourcePath =>
        if (visitedDirs.contains(sourcePath.underlying)) ()
        else if (isCancelled.get) ()
        else {
          Files.walkFileTree(sourcePath.underlying, opts, Int.MaxValue, discovery)
        }
      }
      project.sourcesGlobs.foreach { glob =>
        val discovery = fileVisitor(glob.matches)
        if (isCancelled.get) ()
        else {
          Files.walkFileTree(glob.directory.underlying, opts, glob.walkDepth, discovery)
        }
      }
    }

    val discoverManagedFileTree = Task
      .gatherUnordered {
        project.sourceGenerators.map(updateGenerator)
      }
      .map(_.foreach(_.foreach(path => observer.onNext(path.underlying))))

    val discoverFileTree =
      Task.gatherUnordered(discoverUnmanagedFileTree :: discoverManagedFileTree :: Nil).doOnFinish {
        case Some(t) => Task(observer.onError(t))
        case None => Task(observer.onComplete())
      }

    val collectHashesConsumer: Consumer[HashedSource, mutable.ListBuffer[HashedSource]] = {
      val empty = mutable.ListBuffer.empty[HashedSource]
      FoldLeftAsyncConsumer.consume(empty)((buffer, source) => Task.now(buffer.+=(source)))
    }

    val collectAllSources = Task.create[mutable.ListBuffer[HashedSource]] { (scheduler, cb) =>
      if (isCancelled.get) {
        cb.onSuccess(mutable.ListBuffer.empty)
        subscribed.success(())
        observer.onComplete()
        Cancelable.empty
      } else {
        val (out, consumerSubscription) = collectHashesConsumer.createSubscriber(cb, scheduler)
        val hashSourcesInParallel = observable.mapParallelOrdered(parallelUnits) { (source: Path) =>
          monix.eval.Task.eval {
            val hash = ByteHasher.hashFileContents(source.toFile)
            HashedSource(PlainVirtualFileConverter.converter.toVirtualFile(source), hash)
          }
        }

        val sourceSubscription = hashSourcesInParallel.subscribe(out)
        // We can only start to discover files after we have finalized the subscription
        subscribed.success(())
        Cancelable { () =>
          isCancelled.compareAndSet(false, true)
          try consumerSubscription.cancel()
          finally {
            sourceSubscription.cancel()
          }
        }
      }
    }

    val orderlyDiscovery = Task.fromFuture(subscribed.future).flatMap(_ => discoverFileTree)
    Task
      .mapBoth(orderlyDiscovery, collectAllSources) {
        case (_, sources) =>
          if (!isCancelled.get) Right(sources.toList.distinct)
          else {
            cancelCompilation.trySuccess(())
            Left(())
          }
      }
      .doOnCancel(Task { isCancelled.compareAndSet(false, true); () })
  }
}
