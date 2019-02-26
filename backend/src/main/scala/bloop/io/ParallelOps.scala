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
import scala.collection.JavaConverters._

import monix.eval.Task
import monix.execution.{Scheduler, Cancelable}
import monix.reactive.{Observable, Consumer, Observer}
import monix.reactive.MulticastStrategy

import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}

object ParallelOps {
  case class CopyConfiguration private (
      parallelUnits: Int,
      replaceExisting: Boolean,
      replaceOlderFile: Boolean,
      blacklist: Set[Path]
  )

  def copyDirectories(configuration: CopyConfiguration)(
      origin: Path,
      target: Path,
      scheduler: Scheduler,
      logger: Logger
  ): Task[Unit] = {
    val (observer, observable) = Observable.multicast[(Path, Path)](
      MulticastStrategy.publish
    )(scheduler)

    val discovery = new FileVisitor[Path] {
      var stop: Boolean = false
      var currentTargetDirectory: Path = target
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (attributes.isDirectory || configuration.blacklist.contains(file))
          FileVisitResult.CONTINUE
        else {
          val stop = observer.onNext((file, currentTargetDirectory.resolve(file.getFileName))) == monix.execution.Ack.Stop
          if (!stop) FileVisitResult.CONTINUE
          else {
            // TODO(jvican): Remove this before committing to repository
            logger.error("Stopping file discovery")
            FileVisitResult.CONTINUE
          }
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
        currentTargetDirectory = currentTargetDirectory.resolve(directory.getFileName)
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
      Files.walkFileTree(origin, discovery)
      ()
    }.doOnFinish {
      case Some(t) => Task(observer.onError(t))
      case None => Task(observer.onComplete())
    }

    val copyFilesInParallel = {
      observable.consumeWith(Consumer.foreachParallelAsync(configuration.parallelUnits) {
        case (originFile, targetFile) =>
          Task {
            if (configuration.replaceExisting) {
              Files.copy(
                originFile,
                targetFile,
                StandardCopyOption.COPY_ATTRIBUTES,
                StandardCopyOption.REPLACE_EXISTING
              )
            } else {
              def attrs(path: Path): Option[BasicFileAttributes] = {
                try Some(Files.readAttributes(path, classOf[BasicFileAttributes]))
                catch { case t: IOException => None }
              }

              attrs(targetFile) match {
                case Some(targetAttrs) if configuration.replaceOlderFile =>
                  attrs(originFile) match {
                    case Some(originAttrs)
                        if targetAttrs.lastModifiedTime
                          .compareTo(originAttrs.lastModifiedTime) > 0 =>
                      ()
                    case _ =>
                      Files.copy(
                        originFile,
                        targetFile,
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING
                      )
                  }
                case _ =>
                  // Reading attributes failed, we take it that the file doesn't exist
                  Files.copy(
                    originFile,
                    targetFile,
                    StandardCopyOption.COPY_ATTRIBUTES
                  )
              }
            }
            ()
          }
      })
    }

    Task.gatherUnordered(List(discoverFileTree, copyFilesInParallel)).map(_ => ())
  }

  import scala.util.Try
  private def fileEventConsumer(
      origin: Path,
      target: Path,
      logger: Logger
  ): Consumer[DirectoryChangeEvent, Unit] = {
    val dirAttrs = new ConcurrentHashMap[Path, Boolean]()
    val pathsWithIOErrors = ConcurrentHashMap.newKeySet[Path]()
    val pathsFailedToProcess = ConcurrentHashMap.newKeySet[Path]()
    Consumer.foreachParallelAsync(3) { (event: DirectoryChangeEvent) =>
      // The path may not exist yet, if it doesn't then
      val path = event.path()
      val attrsRead = Try {
        // By default, symbolic links are followed
        Files.readAttributes(path, classOf[BasicFileAttributes])
      }

      def registerIOError[T](path: Path, label: String)(effect: => T): Boolean = {
        try {
          //println(s"${origin.relativize(path)}: $label")
          effect
          true
        } catch {
          case NonFatal(t) =>
            logger.error(s"Failed ${t}")
            pathsFailedToProcess.add(path); false
        }
      }

      def createPath(path: Path, attrs: BasicFileAttributes, newPath: Path): Task[Unit] = Task {
        val dirPath = if (attrs.isDirectory) newPath else newPath.getParent
        // Create the directory if it's a dir or the parent if it's a file first
        val created = dirAttrs.computeIfAbsent(dirPath, (dirPath: Path) => {
          registerIOError(dirPath, "creating dir")(Files.createDirectories(dirPath))
        })

        // We ignore it if it's anything else than a file (symlink)
        if (created && attrs.isRegularFile) {
          registerIOError(path, "creating file") {
            Files.copy(
              path,
              newPath,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES
            )
          }
          ()
        }
      }

      def modifyPath(path: Path, attrs: BasicFileAttributes, newPath: Path): Task[Unit] = Task {
        if (attrs.isRegularFile) {
          // When a path has been modified we assume the parent already exists and copy right away
          registerIOError(path, "modifying file") {
            Files.copy(
              path,
              newPath,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES
            )
          }
          ()
        }
      }

      def deletePath(path: Path, attrs: BasicFileAttributes, newPath: Path): Task[Unit] = Task {
        registerIOError(path, "deleting file/dir")(Files.deleteIfExists(newPath))
        ()
      }

      attrsRead match {
        case scala.util.Success(attrs) =>
          val newPath = target.resolve(origin.relativize(path))
          event.eventType match {
            case EventType.CREATE => createPath(path, attrs, newPath)
            case EventType.MODIFY => modifyPath(path, attrs, newPath)
            case EventType.OVERFLOW => Task.now(())
            case EventType.DELETE => deletePath(path, attrs, newPath)
          }
        case scala.util.Failure(e) =>
          pathsWithIOErrors.add(path)
          Task.now(())
      }
    }
  }
}
