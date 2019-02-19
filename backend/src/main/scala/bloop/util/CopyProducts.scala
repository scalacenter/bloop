package bloop.util

import bloop.logging.Logger

import java.nio.file.{Path, Files}
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption

import scala.util.control.NonFatal
import scala.collection.JavaConverters._

import monix.eval.Task
import monix.execution.{Scheduler, Cancelable}
import monix.reactive.{Observable, Consumer, Observer}
import monix.reactive.MulticastStrategy

import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}

object CopyProducts {
  def apply(
      origin: Path,
      target0: Path,
      scheduler: Scheduler,
      executor: Executor,
      logger0: Logger
  ): (Task[Unit], Cancelable) = {
    // Make sure that the target is the real path and not a symlink
    val target = target0.toRealPath()
    var watchingEnabled: Boolean = true
    val logger = new bloop.logging.Slf4jAdapter(logger0)

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(scheduler)

    val listener = new DirectoryChangeListener {
      override def isWatching: Boolean = watchingEnabled
      override def onException(e: Exception): Unit = ()
      override def onEvent(event: DirectoryChangeEvent): Unit = {
        println(s"Event ${event.eventType} ${event.path}")
        observer.onNext(event)
        ()
      }
    }

    val watcher = DirectoryWatcher
      .builder()
      .paths(List(origin).asJava)
      .listener(listener)
      .fileHashing(true)
      .build()

    val watcherHandle = watcher.watchAsync(executor)
    val watchCancellation = Cancelable { () =>
      watchingEnabled = false
      watcher.close()
      watcherHandle.complete(null)
      observer.onComplete()
      logger.debug("Cancelling file watcher")
    }

    val dirAttrs = new ConcurrentHashMap[Path, Boolean]()
    val pathsWithIOErrors = ConcurrentHashMap.newKeySet[Path]()
    val pathsFailedToProcess = ConcurrentHashMap.newKeySet[Path]()

    import scala.util.Try
    val fileEventConsumer: Consumer[DirectoryChangeEvent, Unit] = {
      Consumer.foreachParallelAsync(3) { (event: DirectoryChangeEvent) =>
        // The path may not exist yet, if it doesn't then
        val path = event.path()
        val attrsRead = Try {
          // By default, symbolic links are followed
          Files.readAttributes(path, classOf[BasicFileAttributes])
        }

        def registerIOError[T](path: Path, label: String)(effect: => T): Boolean = {
          try {
            effect
            true
          } catch {
            case NonFatal(t) =>
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

    val consumeTask = observable.consumeWith(fileEventConsumer)
    consumeTask -> watchCancellation
  }
}
