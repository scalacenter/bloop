package bloop.io

import java.nio.file.{Files, Path}

import bloop.bsp.BspServer
import bloop.engine.{ExecutionContext, State}
import bloop.logging.{DebugFilter, Logger, Slf4jAdapter}
import bloop.util.monix.FoldLeftAsyncConsumer

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}

import monix.eval.Task
import monix.execution.Cancelable
import monix.reactive.{MulticastStrategy, Observable}

final class SourceWatcher private (
    projectNames: List[String],
    dirs: Seq[Path],
    files: Seq[Path],
    logger: Logger
) {
  import java.nio.file.Files
  private val slf4jLogger = new Slf4jAdapter(logger)

  private implicit val logContext: DebugFilter = DebugFilter.FileWatching

  def watch(state0: State, action: State => Task[State]): Task[State] = {
    val ngout = state0.commonOptions.ngout
    def runAction(state: State, events: Seq[DirectoryChangeEvent]): Task[State] = {
      // Windows is not supported for now
      if (!bloop.util.CrossPlatform.isWindows)
        logger.info("\u001b[H\u001b[2J") // Clean terminal before acting on the event action
      events.foreach(e => logger.debug(s"A ${e.eventType()} in ${e.path()} has triggered an event"))
      action(state)
    }

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(
        ExecutionContext.ioScheduler
      )

    var watchingEnabled: Boolean = true
    val listener = new DirectoryChangeListener {
      override def isWatching: Boolean = watchingEnabled

      // Make sure that errors on the file watcher are reported back
      override def onException(e: Exception): Unit = {
        slf4jLogger.error(s"File watching threw an exception: ${e.getMessage}")
        // Enable tracing when https://github.com/scalacenter/bloop/issues/433 is done
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
    }

    val watcher = DirectoryWatcher
      .builder()
      .paths(dirs.asJava)
      .files(files.asJava)
      .logger(slf4jLogger)
      .listener(listener)
      .fileHashing(true)
      .build();

    // Use Java's completable future because we can stop/complete it from the cancelable
    val watcherHandle = watcher.watchAsync(ExecutionContext.ioExecutor)
    val watchController = Task {
      try watcherHandle.get()
      finally watcher.close()
      logger.debug("File watcher was successfully closed")
    }

    val watchCancellation = Cancelable { () =>
      watchingEnabled = false
      watcherHandle.complete(null)
      observer.onComplete()
      ngout.println(
        s"File watching on '${projectNames.mkString("', '")}' and dependent projects has been successfully cancelled"
      )
    }

    import SourceWatcher.EventStream
    @volatile var currentState: State = state0
    val fileEventConsumer = {
      import monix.reactive.Consumer
      Consumer.foreachAsync { (e: EventStream) =>
        e match {
          case EventStream.Overflow => Task.unit
          case EventStream.SourceChanges(events) =>
            val eventsThatForceAction = events.collect { event =>
              event.eventType match {
                case EventType.CREATE => event
                case EventType.MODIFY => event
                case EventType.OVERFLOW => event
              }
            }

            eventsThatForceAction match {
              case Nil => Task.unit
              case events =>
                runAction(currentState, events).map { state =>
                  currentState = state
                }
            }
        }
      }
    }

    /*
     * We capture events during a time window of 20ms to give time to the OS to
     * give us all of the modifications to files. After that, we trigger the
     * action and we don't emit more sources while the action runs. If there
     * have been more file watching events happening while the action was
     * running, at the end we send an overflow and check if the source inputs
     * have changed. If they have, we force another action, otherwise we call it
     * a day and wait for the next events.
     */
    observable
      .bufferTimed(FiniteDuration(20, "ms"))
      .map(es => EventStream.SourceChanges(es))
      .whileBusyDropEventsAndSignal(_ => SourceWatcher.EventStream.Overflow)
      .consumeWith(fileEventConsumer)
      .doOnCancel(Task(watchCancellation.cancel()))
      .map(_ => currentState)
  }

  def notifyWatch(): Unit = {
    val filesCount = files.size
    val dirsCount = dirs.size
    val andFiles = if (filesCount == 0) "" else s" and $filesCount files"
    logger.info(s"Watching $dirsCount directories$andFiles... (press Ctrl-C to interrupt)")
  }
}

object SourceWatcher {
  def apply(projectNames: List[String], paths0: Seq[Path], logger: Logger): SourceWatcher = {
    val existingPaths = paths0.distinct.filter(p => Files.exists(p))
    val dirs = existingPaths.filter(p => Files.isDirectory(p))
    val files = existingPaths.filter(p => Files.isRegularFile(p))
    new SourceWatcher(projectNames, dirs, files, logger)
  }

  sealed trait EventStream
  object EventStream {
    case object Overflow extends EventStream
    case class SourceChanges(es: Seq[DirectoryChangeEvent]) extends EventStream
  }
}
