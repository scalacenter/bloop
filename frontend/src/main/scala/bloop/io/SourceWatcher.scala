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

  private final val fakePath = java.nio.file.Paths.get("$$$bloop-monix-trigger-first-event$$$")
  private final val triggerCompilationEvent =
    new DirectoryChangeEvent(DirectoryChangeEvent.EventType.OVERFLOW, fakePath, -1)

  def watch(state0: State, action: State => Task[State]): Task[State] = {
    logger.info(s"Watching the following directories: ${dirs.mkString(", ")}")

    var lastState: State = state0
    def runAction(state: State, event: DirectoryChangeEvent): Task[State] = {
      Task(logger.info(s"A ${event.eventType()} in ${event.path()} has triggered an event."))
        .flatMap((_: Unit) => lastState = action(state))
        .map(state => { lastState = state; state })
    }

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(
        ExecutionContext.ioScheduler)

    val compilationTask = observable
      .foldLeftL(runAction(lastState, triggerCompilationEvent)) {
        case (state, e) =>
          e.eventType match {
            case EventType.CREATE => runAction(state, e)
            case EventType.MODIFY => runAction(state, e)
            case EventType.OVERFLOW => runAction(state, e)
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
    }

    val aggregated = Task.zip2(
      watchingTask.executeOn(ExecutionContext.ioScheduler),
      compilationTask.executeOn(state0.scheduler)
    )

    aggregated
      .map {
        case (Success(_), state) => state
        case (Failure(t), state) =>
          state.logger.error("Unexpected file watching error")
          state.logger.trace(t)
          state.mergeStatus(ExitStatus.UnexpectedError)
      }
      .doOnCancel(Task(watcher.close()))
      .doOnFinish(Task(watcher.close()))
  }
}
