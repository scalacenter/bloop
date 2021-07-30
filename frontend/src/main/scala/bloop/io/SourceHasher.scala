package bloop.io

import java.io.IOException
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable
import scala.concurrent.Promise

import bloop.UniqueCompileInputs.HashedSource
import bloop.data.Project

import monix.eval.Task
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
   * @param parallelUnits The amount of sources we can hash at once.
   * @param cancelCompilation A promise that will be completed if task is cancelled.
   * @param scheduler The scheduler that should be used for internal Monix usage.
   * @param logger The logger where every action will be logged.
   */
  def findAndHashSourcesInProject(
      project: Project,
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

    val discoverFileTree = Task {
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
    }.doOnFinish {
      case Some(t) => Task(observer.onError(t))
      case None => Task(observer.onComplete())
    }

    val collectHashesConsumer: Consumer[HashedSource, mutable.ListBuffer[HashedSource]] = {
      val empty = mutable.ListBuffer.empty[HashedSource]
      Consumer.foldLeft(empty)((buffer, source) => buffer.+=(source))
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
          Task.eval {
            val hash = ByteHasher.hashFileContents(source.toFile)
            HashedSource(PlainVirtualFileConverter.converter.toVirtualFile(source), hash)
          }
        }

        val sourceSubscription = hashSourcesInParallel.subscribe(out)
        // We can only start to discover files after we have finalized the subscription
        subscribed.success(())
        Cancelable { () =>
          isCancelled.compareAndSet(false, true)
          sourceSubscription.cancel()
          cb.onSuccess(mutable.ListBuffer.empty)
        }
      }
    }

    val orderlyDiscovery = Task.fromFuture(subscribed.future).flatMap(_ => discoverFileTree)
    val computation = Task
      .mapBoth(orderlyDiscovery, collectAllSources) {
        case (_, sources) => Right(sources.toList.distinct)
      }
      .doOnCancel(Task(cancelCompilation.success(())))

    val fallback: Task[Either[Unit, List[HashedSource]]] =
      Task.fromFuture(cancelCompilation.future).map(_ => Left(())).uncancelable

    Task
      .race(computation, fallback)
      .map {
        case Left(computedHashes) => computedHashes
        case Right(fallback) => fallback
      }
      .doOnCancel(Task { isCancelled.compareAndSet(false, true); () })
  }
}
