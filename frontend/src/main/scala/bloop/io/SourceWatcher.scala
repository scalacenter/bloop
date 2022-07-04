package bloop.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

import bloop.engine.ExecutionContext
import bloop.engine.State
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.logging.Slf4jAdapter
import bloop.util.monix.FoldLeftAsyncConsumer

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.atomic.AtomicBoolean
import monix.reactive.MulticastStrategy
import monix.reactive.Observable

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
        slf4jLogger.debug(s"File watching threw an exception: ${e.getMessage}")
        // Enable tracing when https://github.com/scalacenter/bloop/issues/433 is done
        // logger.trace(e)
      }

      private[this] val scheduledResubmissions = new ConcurrentHashMap[Path, Cancelable]()
      override def onEvent(event: DirectoryChangeEvent): Unit = {
        val targetFile = event.path()
        val attrs = Files.readAttributes(targetFile, classOf[BasicFileAttributes])

        if (attrs.isRegularFile && SourceHasher.matchSourceFile(targetFile)) {
          if (attrs.size != 0L) {
            val resubmission = scheduledResubmissions.remove(targetFile)
            if (resubmission != null) resubmission.cancel()
            observer.onNext(event)
          } else {
            /*
             * This is a workaround for an issue that happens when using
             * "Remote Development" in VS Code. When the user runs the "Save
             * file" action in their remote frontend, the current
             * implementation in the server seems to cause two side effects:
             *
             * 1. First, it empties out the file with size == 0.
             * 2. Then, it fills in the new contents of the saved file.
             *
             * One would expect that the save action is atomic, but in this
             * scenario it isn't. Therefore, sometimes Metals sends a compile
             * request between the two side effects, meaning it can get to
             * compile the project with an empty source file which usually
             * generates errors. If the file watcher is running in the
             * background, it acts on the first side effect and deduplicates
             * from BSP, the errors will be shown in the terminal. This
             * workaround here waits 500ms before acting on a MODIFY event for
             * a file with size == 0, which means the file watcher will not act
             * on the first side effect but the second. If the second was never
             * to happen after 500ms, then the first side effect would be
             * resubmitted and would generate a build action.
             */
            val scheduled = ExecutionContext.ioScheduler.scheduleOnce(
              500,
              TimeUnit.MILLISECONDS,
              new Runnable {
                def run(): Unit = {
                  scheduledResubmissions.remove(targetFile)
                  if (Files.size(targetFile) == 0) {
                    observer.onNext(event)
                  }
                  ()
                }
              }
            )
            scheduledResubmissions.putIfAbsent(targetFile, scheduled)
          }
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

    val watchLogId = s"File watching on '${projectNames.mkString("', '")}' and dependent projects"
    val isClosed = AtomicBoolean.apply(false)

    // Use Java's completable future because we can stop/complete it from the cancelable
    val watcherHandle = watcher.watchAsync(ExecutionContext.ioExecutor)

    val watchCancellation = Cancelable { () =>
      observer.onComplete()
      watchingEnabled = false

      // Cancel the future to interrupt blocking event polling of file stream
      watcherHandle.cancel(true)

      // Complete future to force the controller to close the watcher
      watcherHandle.complete(null)

      // Force closing the file watcher in case it doesn't work
      if (isClosed.compareAndSet(false, true)) {
        watcher.close()
      }

      ngout.println(s"$watchLogId has been successfully cancelled")
    }

    val fileEventConsumer = {
      FoldLeftAsyncConsumer.consume[State, Seq[DirectoryChangeEvent]](state0) {
        case (state, events) =>
          logger.debug(s"Received $events in file watcher consumer")
          val eventsThatForceAction = events.collect { event =>
            event.eventType match {
              case EventType.CREATE => event
              case EventType.MODIFY => event
              case EventType.OVERFLOW => event
            }
          }

          eventsThatForceAction match {
            case Nil => Task.now(state)
            case events => runAction(state, events)
          }
      }
    }

    /*
     * We capture events during a time window of 40ms to give time to the OS to
     * give us all of the modifications to files. After that, we trigger the
     * action and we don't emit more sources while the action runs. If there
     * have been more file watching events happening while the action was
     * running, at the end we send an overflow and check if the source inputs
     * have changed. If they have, we force another action, otherwise we call it
     * a day and wait for the next events.
     */

    import bloop.util.monix.{BloopBufferTimedObservable, BloopWhileBusyDropEventsAndSignalOperator}
    val timespan = {
      val userMs = Integer.getInteger("file-watcher-batch-window-ms")
      if (userMs == null) FiniteDuration(20L, "ms")
      else FiniteDuration(userMs.toLong, "ms")
    }

    observable
      .transform(self => new BloopBufferTimedObservable(self, timespan, 0))
      .liftByOperator(
        new BloopWhileBusyDropEventsAndSignalOperator((es: Seq[Seq[DirectoryChangeEvent]]) =>
          es.flatten
        )
      )
      .consumeWith(fileEventConsumer)
      .doOnCancel(Task(watchCancellation.cancel()))
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
