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

import scala.collection.mutable
import scala.concurrent.Promise

import bloop.data.Project
import bloop.CompilerOracle
import bloop.engine.ExecutionContext
import bloop.util.monix.FoldLeftAsyncConsumer
import bloop.UniqueCompileInputs.HashedSource

import monix.reactive.{MulticastStrategy, Consumer, Observable}
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import monix.execution.Scheduler
import monix.reactive.internal.operators.MapAsyncParallelObservable
import monix.execution.Cancelable
import monix.execution.cancelables.CompositeCancelable

object SourceHasher {
  private final val sourceMatcher = FileSystems.getDefault.getPathMatcher("glob:**.{scala,java}")

  /**
   * Find sources in a project and hash them in parallel.
   *
   * NOTE: When the task returned by this method is cancelled, the promise
   * `cancelCompilation` will be completed and the returned value will be
   * empty. The call-site needs to handle the case where cancellation happens.
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
    val discovery = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (isCancelled.get) FileVisitResult.TERMINATE
        else {
          if (!sourceMatcher.matches(file)) ()
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
      val opts = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
      sourceFilesAndDirectories.foreach { sourcePath =>
        if (visitedDirs.contains(sourcePath.underlying)) ()
        else if (isCancelled.get) ()
        else {
          Files.walkFileTree(sourcePath.underlying, opts, Int.MaxValue, discovery)
        }
      }
    }.doOnFinish {
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
        Cancelable.empty
      } else {
        val (out, consumerSubscription) = collectHashesConsumer.createSubscriber(cb, scheduler)
        val hashSourcesInParallel = observable.mapAsync(parallelUnits) { (source: Path) =>
          Task.eval {
            val hash = ByteHasher.hashFileContents(source.toFile)
            HashedSource(AbsolutePath(source), hash)
          }
        }

        val sourceSubscription = hashSourcesInParallel.subscribe(out)
        // We can only start to discover files after we have finalized the subscription
        subscribed.success(())
        Cancelable { () =>
          observer.onComplete()
          isCancelled.compareAndSet(false, true)
          try consumerSubscription.cancel()
          finally {
            sourceSubscription.cancel()
          }
        }
      }
    }

    val orderlyDiscovery = Task.fromFuture(subscribed.future).flatMap(_ => discoverFileTree)
    Task.mapBoth(orderlyDiscovery, collectAllSources) {
      case (_, sources) =>
        if (!isCancelled.get) Right(sources.toList.distinct)
        else {
          cancelCompilation.trySuccess(())
          Left(())
        }
    }
  }
}
