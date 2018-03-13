package bloop.io

import java.nio.file.Path

import bloop.cli.ExitStatus
import bloop.engine.{ExecutionContext, State}
import bloop.logging.Logger

import scala.collection.JavaConverters._
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}
import monix.eval.Task
import monix.reactive.{MulticastStrategy, Observable}

final class SourceWatcher(dirs0: Seq[Path], logger: Logger) {
  private val dirs = dirs0.distinct
  private val dirsAsJava: java.util.List[Path] = dirs.asJava

  // Create source directories if they don't exist, otherwise the watcher fails.
  import java.nio.file.Files
  dirs.foreach(p => if (!Files.exists(p)) Files.createDirectories(p) else ())

  def watch(initialState: State, action: State => State): State = {
    logger.debug(s"Watching the following directories: ${dirs.mkString(", ")}")
    var result: State = initialState
    def runAction(event: DirectoryChangeEvent): Unit = {
      logger.debug(s"A ${event.eventType()} in ${event.path()} has triggered an event.")
      result = action(result)
    }

    val watcher = DirectoryWatcher.create(
      dirsAsJava,
      new DirectoryChangeListener {
        override def onEvent(event: DirectoryChangeEvent): Unit = {
          val targetFile = event.path()
          val targetPath = targetFile.toFile.getAbsolutePath()
          if (Files.isRegularFile(targetFile) &&
              (targetPath.endsWith(".scala") || targetPath.endsWith(".java"))) {
            event.eventType() match {
              case EventType.CREATE => runAction(event)
              case EventType.MODIFY => runAction(event)
              case EventType.OVERFLOW => runAction(event)
              case EventType.DELETE => () // We don't do anything when a file is deleted
            }
          }
        }
      }
    )
    try { watcher.watch(); result } catch {
      case t: Throwable =>
        logger.error("Unexpected error happened when file watching.")
        logger.trace(t)
        result.mergeStatus(ExitStatus.UnexpectedError)
    }
  }

  private final val fakePath = java.nio.file.Paths.get("$$$bloop-monix-trigger-first-event$$$")
  private final val triggerEventAtFirst =
    new DirectoryChangeEvent(DirectoryChangeEvent.EventType.OVERFLOW, fakePath, -1)

  def watch(state0: State, action: State => Task[State]): Task[State] = {
    logger.debug(s"Watching the following directories: ${dirs.mkString(", ")}")

    def runAction(state: State, event: DirectoryChangeEvent): Task[State] = {
      Task(logger.debug(s"A ${event.eventType()} in ${event.path()} has triggered an event."))
        .flatMap(_ => action(state))
        .executeOn(state0.scheduler)
    }

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(
        ExecutionContext.ioScheduler)

    val compilationTask = observable
      .foldLeftL(action(state0)) {
        case (stateTask, e) =>
          e.eventType match {
            case EventType.CREATE => stateTask.flatMap(s => runAction(s, e))
            case EventType.MODIFY => stateTask.flatMap(s => runAction(s, e))
            case EventType.OVERFLOW => stateTask.flatMap(s => runAction(s, e))
            case EventType.DELETE => stateTask
          }
      }
      .flatten

    val watcher = DirectoryWatcher.create(
      dirsAsJava,
      new DirectoryChangeListener {
        @volatile var stop: Boolean = false
        override def onEvent(event: DirectoryChangeEvent): Unit = {
          val targetFile = event.path()
          val targetPath = targetFile.toFile.getAbsolutePath()
          if (Files.isRegularFile(targetFile) &&
              (targetPath.endsWith(".scala") || targetPath.endsWith(".java"))) {
            val ack = observer.onNext(event)
            stop = ack.isCompleted
          }
        }
      }
    )

    import scala.util.{Try, Success, Failure}
    val watchingTask = Task {
      Try {
        try watcher.watch()
        finally watcher.close()
      }
    }.doOnCancel(Task(watcher.close()))

    val firstEventTriggerTask = Task(observer.onNext(triggerEventAtFirst))
    val aggregated = Task.zip3(
      firstEventTriggerTask.executeOn(ExecutionContext.ioScheduler),
      watchingTask.executeOn(ExecutionContext.ioScheduler),
      compilationTask.executeOn(state0.scheduler)
    )

    aggregated.map {
      case (_, Success(_), state) => state
      case (_, Failure(t), state) =>
        state.logger.error("Unexpected file watching error")
        state.logger.trace(t)
        state.mergeStatus(ExitStatus.UnexpectedError)
    }
  }
}
