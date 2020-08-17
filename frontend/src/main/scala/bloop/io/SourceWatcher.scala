package bloop.io

import java.nio.file.{Files, Path}

import bloop.bsp.BspServer
import bloop.engine.{ExecutionContext, State}
import bloop.logging.{DebugFilter, Logger, Slf4jAdapter}
import bloop.util.monix.FoldLeftAsyncConsumer

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import com.swoval.files.{FileTreeRepositories, FileTreeRepository, TypedPath}
import com.swoval.logging.{Logger => SLogger, Loggers}
import com.swoval.files.FileTreeDataViews.{CacheObserver, Entry}
import com.swoval.files.FileTreeViews.Observer

import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}

import monix.eval.Task
import monix.reactive.Consumer
import monix.execution.Cancelable
import monix.reactive.{MulticastStrategy, Observable}
import monix.execution.misc.NonFatal
import monix.execution.atomic.AtomicBoolean
import monix.reactive.OverflowStrategy
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.nio.file.attribute.BasicFileAttributes

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
    val swovalObserver = new CacheObserver[TypedPath] {

      // Make sure that errors on the file watcher are reported back
      override def onError(e: IOException): Unit = {
        slf4jLogger.debug(s"File watching threw an exception: ${e.getMessage}")
        // Enable tracing when https://github.com/scalacenter/bloop/issues/433 is done
        //logger.trace(e)
      }

      private[this] val scheduledResubmissions = new ConcurrentHashMap[Path, Cancelable]()
      override def onCreate(entry: Entry[TypedPath]): Unit =
        if (watchingEnabled) onEvent(entry, DirectoryChangeEvent.EventType.CREATE)
      override def onDelete(entry: Entry[TypedPath]): Unit =
        if (watchingEnabled) onEvent(entry, DirectoryChangeEvent.EventType.DELETE)
      override def onUpdate(previous: Entry[TypedPath], current: Entry[TypedPath]): Unit =
        if (watchingEnabled) onEvent(current, DirectoryChangeEvent.EventType.MODIFY)
      def onEvent(entry: Entry[TypedPath], kind: DirectoryChangeEvent.EventType): Unit = {
        val targetFile = entry.getTypedPath.getPath()
        val event = new DirectoryChangeEvent(kind, targetFile, 1)

        if (entry.getTypedPath.isFile && SourceHasher.matchSourceFile(targetFile)) {
          val defer =
            if (kind == DirectoryChangeEvent.EventType.MODIFY)
              Try(Files.size(targetFile)).getOrElse(0L) == 0L
            else kind == DirectoryChangeEvent.EventType.DELETE
          if (!defer) {
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
                  if (Try(Files.size(targetFile)).getOrElse(0L) == 0L) {
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

    val watcher = FileTreeRepositories.get(
      tp => tp, // FileTreeRepositories cache data associated with a TypedPath
      true, // follows symlinks
      false, // disables automatic rescanning of directories if a root directory change is detected
      new SLogger {
        def debug(s: String): Unit = slf4jLogger.debug(s)
        def error(s: String): Unit = slf4jLogger.error(s)
        def info(s: String): Unit = slf4jLogger.info(s)
        def getLevel(): Loggers.Level = Loggers.Level.DEBUG
        def verbose(s: String): Unit = {}
        def warn(s: String): Unit = slf4jLogger.warn(s)
      }
    )
    val swovalHandle = watcher.addCacheObserver(swovalObserver)
    def realPath(p: Path) = Try(p.toRealPath()).getOrElse(p)
    dirs.foreach(p => watcher.register(realPath(p), Int.MaxValue)) // Int.MaxValue is a recursive watch
    files.foreach(p => watcher.register(realPath(p), -1)) // -1 indicates only watch the file itself

    val watchLogId = s"File watching on '${projectNames.mkString("', '")}' and dependent projects"
    val isClosed = AtomicBoolean.apply(false)

    val watchCancellation = Cancelable { () =>
      observer.onComplete()
      watchingEnabled = false

      watcher.removeObserver(swovalHandle)
      watcher.close()

      // Force closing the file watcher in case it doesn't work
      if (isClosed.compareAndSet(false, true)) {
        watcher.close()
      }

      ngout.println(s"$watchLogId has been successfully cancelled")
    }

    import SourceWatcher.EventStream
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
        new BloopWhileBusyDropEventsAndSignalOperator(
          (es: Seq[Seq[DirectoryChangeEvent]]) => es.flatten
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
