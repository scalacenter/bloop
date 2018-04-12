package bloop.io

import java.nio.file.Path

import bloop.Project
import bloop.bsp.BspServer
import bloop.engine.{ExecutionContext, State}
import bloop.logging.{Logger, Slf4jAdapter}
import bloop.monix.FoldLeftAsyncConsumer

import scala.collection.JavaConverters._
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}
import monix.eval.Task
import monix.execution.Cancelable
import monix.reactive.{MulticastStrategy, Observable}

final class SourceWatcher(project: Project, paths0: Seq[Path], logger: Logger) {
  import java.nio.file.Files
  private val paths = paths0.distinct
  private val dirs = paths.filter(p => Files.isDirectory(p))
  private val dirsCount = dirs.size
  private val filesCount = paths.filter(p => Files.isRegularFile(p)).size
  private val slf4jLogger = new Slf4jAdapter(logger)

  // Create source directories if they don't exist, otherwise the watcher fails.
  dirs.foreach(p => if (!Files.exists(p)) Files.createDirectories(p) else ())

  def watch(state0: State, action: State => Task[State]): Task[State] = {
    val ngout = state0.commonOptions.ngout
    def runAction(state: State, event: DirectoryChangeEvent): Task[State] = {
      // Someone that wants this to be supported by Windows will need to make it work for all terminals
      if (!BspServer.isWindows)
        logger.info("\u001b[H\u001b[2J") // Clean the terminal before acting on the file event action
      logger.debug(s"A ${event.eventType()} in ${event.path()} has triggered an event.")
      action(state)
    }

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(
        ExecutionContext.ioScheduler)

    var watchingEnabled: Boolean = true
    val watcher = DirectoryWatcher.create(
      paths.asJava,
      new DirectoryChangeListener {
        // Define `isWatching` just for correctness
        override def isWatching: Boolean = watchingEnabled

        // Make sure that errors on the file watcher are reported back
        override def onException(e: Exception, logger: org.slf4j.Logger): Unit = {
          logger.error(s"File watching threw an exception: ${e.getMessage}")
          // Enable back when https://github.com/scalacenter/bloop/issues/433 is done
          //logger.trace(e)
        }

        override def onEvent(event: DirectoryChangeEvent): Unit = {
          val targetFile = event.path()
          val targetPath = targetFile.toFile.getAbsolutePath()
          if (Files.isRegularFile(targetFile) &&
              (targetPath.endsWith(".scala") || targetPath.endsWith(".java"))) {
            observer.onNext(event)
            ()
          }
        }
      },
      slf4jLogger
    )

    // Use Java's completable future because we can stop/complete it from the cancelable
    val watcherHandle = watcher.watchAsync(ExecutionContext.ioExecutor)
    val watchController = Task {
      try watcherHandle.get()
      finally watcher.close()
      logger.debug("The file watcher was successfully closed.")
    }

    val watchCancellation = Cancelable { () =>
      watchingEnabled = false
      watcherHandle.complete(null)
      observer.onComplete()
      ngout.println(
        s"File watching on '${project.name}' and dependent projects has been successfully cancelled.")
    }

    val fileEventConsumer = {
      FoldLeftAsyncConsumer.consume[State, DirectoryChangeEvent](state0) {
        case (state, event) =>
          event.eventType match {
            case EventType.CREATE => runAction(state, event)
            case EventType.MODIFY => runAction(state, event)
            case EventType.OVERFLOW => runAction(state, event)
            case EventType.DELETE => Task.now(state)
          }
      }
    }

    val watchHandle = watchController.runAsync(ExecutionContext.ioScheduler)
    observable
      .consumeWith(fileEventConsumer)
      .doOnCancel(Task(watchCancellation.cancel()))
  }

  def notifyWatch(): Unit = {
    val andFiles = if (filesCount == 0) "" else s" and $filesCount files"
    logger.info(s"Watching $dirsCount directories$andFiles... (press C-c to interrupt)")
  }
}
