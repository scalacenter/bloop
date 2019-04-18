package bloop.io

import bloop.logging.Logger

import java.io.{IOException, File}
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitOption,
  FileVisitResult,
  FileVisitor,
  Files,
  Path,
  SimpleFileVisitor,
  StandardCopyOption
}

import scala.util.control.NonFatal
import scala.concurrent.Promise
import scala.collection.JavaConverters._

import monix.eval.Task
import monix.execution.{Scheduler, Cancelable}
import monix.reactive.{Observable, Consumer, Observer}
import monix.reactive.MulticastStrategy

import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}

object ParallelOps {

  sealed trait CopyMode
  object CopyMode {
    final case object NoReplace extends CopyMode
    final case object ReplaceExisting extends CopyMode
    final case object ReplaceIfMetadataMismatch extends CopyMode
  }

  /**
   * A configuration for a copy process.
   *
   * @param parallelUnits Threads to use for parallel IO copy.
   * @param replaceExisting Whether the copy should replace existing paths in the target.
   * @param blacklist A list of both origin and target paths that if matched cancel compilation.
   */
  case class CopyConfiguration private (
      parallelUnits: Int,
      mode: CopyMode,
      blacklist: Set[Path]
  )

  case class FileWalk(visited: List[Path], target: List[Path])

  private[this] val takenByOtherCopyProcess = new ConcurrentHashMap[Path, Promise[Unit]]()

  /**
   * Copies files from [[origin]] to [[target]] with the provided copy
   * configuration in parallel.
   *
   * @return The list of paths that have been copied.
   */
  def copyDirectories(configuration: CopyConfiguration)(
      origin: Path,
      target: Path,
      scheduler: Scheduler,
      logger: Logger
  ): Task[FileWalk] = {
    import scala.collection.mutable
    val visitedPaths = new mutable.ListBuffer[Path]()
    val targetPaths = new mutable.ListBuffer[Path]()
    val (observer, observable) = Observable.multicast[((Path, BasicFileAttributes), Path)](
      MulticastStrategy.publish
    )(scheduler)

    val discovery = new FileVisitor[Path] {
      var stop: Boolean = false
      var firstVisit: Boolean = true
      var currentTargetDirectory: Path = target
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (attributes.isDirectory || configuration.blacklist.contains(file)) ()
        else {
          val rebasedFile = currentTargetDirectory.resolve(file.getFileName)
          if (configuration.blacklist.contains(rebasedFile)) ()
          else {
            visitedPaths.+=(file)
            targetPaths.+=(rebasedFile)
            observer.onNext((file -> attributes, rebasedFile))
          }
        }
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
        if (firstVisit) {
          firstVisit = false
        } else {
          currentTargetDirectory = currentTargetDirectory.resolve(directory.getFileName)
        }
        Files.createDirectories(currentTargetDirectory)
        FileVisitResult.CONTINUE
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.getParent()
        FileVisitResult.CONTINUE
      }
    }

    val discoverFileTree = Task {
      if (!Files.exists(origin)) {
        FileWalk(Nil, Nil)
      } else {
        Files.walkFileTree(origin, discovery)
        FileWalk(visitedPaths.toList, targetPaths.toList)
      }
    }.doOnFinish {
      case Some(t) => Task(observer.onError(t))
      case None => Task(observer.onComplete())
    }

    val subscribed = Promise[Unit]()
    val copyFilesInParallel = {
      observable
        .doOnSubscribe(() => subscribed.success(()))
        .consumeWith(Consumer.foreachParallelAsync(configuration.parallelUnits) {
          case ((originFile, originAttrs), targetFile) =>
            def copy(replaceExisting: Boolean): Unit = {
              if (replaceExisting) {
                Files.copy(
                  originFile,
                  targetFile,
                  StandardCopyOption.COPY_ATTRIBUTES,
                  StandardCopyOption.REPLACE_EXISTING
                )
              } else {
                Files.copy(
                  originFile,
                  targetFile,
                  StandardCopyOption.COPY_ATTRIBUTES
                )
              }
              ()
            }

            def triggerCopy(p: Promise[Unit]) = Task.eval {
              try {
                configuration.mode match {
                  case CopyMode.ReplaceExisting => copy(replaceExisting = true)
                  case CopyMode.ReplaceIfMetadataMismatch =>
                    import scala.util.{Try, Success, Failure}
                    Try(Files.readAttributes(targetFile, classOf[BasicFileAttributes])) match {
                      case Success(targetAttrs) =>
                        val changedMetadata = {
                          originAttrs.lastModifiedTime
                            .compareTo(targetAttrs.lastModifiedTime) != 0 ||
                          originAttrs.size() != targetAttrs.size()
                        }

                        if (!changedMetadata) ()
                        else copy(replaceExisting = true)
                      // Can happen when the file does not exist, replace in that case
                      case Failure(t: IOException) => copy(replaceExisting = true)
                      case Failure(t) => throw t
                    }
                  case CopyMode.NoReplace =>
                    if (Files.exists(targetFile)) ()
                    else copy(replaceExisting = false)
                }
              } finally {
                takenByOtherCopyProcess.remove(originFile)
                // Complete successfully to unblock other tasks
                p.success(())
              }
              ()
            }

            def acquireFile: Task[Unit] = {
              val currentPromise = Promise[Unit]()
              val promiseInMap = takenByOtherCopyProcess.putIfAbsent(originFile, currentPromise)
              if (promiseInMap == null) {
                triggerCopy(currentPromise)
              } else {
                Task.fromFuture(promiseInMap.future).flatMap(_ => acquireFile)
              }
            }

            acquireFile
        })
    }

    val orderlyDiscovery = Task.fromFuture(subscribed.future).flatMap(_ => discoverFileTree)
    Task {
      Task.mapBoth(orderlyDiscovery, copyFilesInParallel) { case (fileWalk, _) => fileWalk }
    }.flatten.executeOn(scheduler)
  }
}
