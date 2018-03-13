package bloop.io

import java.nio.file.Path

import bloop.cli.ExitStatus
import bloop.engine.{ExecutionContext, State}
import bloop.logging.Logger

import scala.collection.JavaConverters._
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}
import monix.eval.Task
import monix.reactive.{Consumer, MulticastStrategy, Observable}

final class SourceWatcher(dirs0: Seq[Path], logger: Logger) {
  private val dirs = dirs0.distinct
  private val dirsAsJava: java.util.List[Path] = dirs.asJava

  // Create source directories if they don't exist, otherwise the watcher fails.
  import java.nio.file.Files
  dirs.foreach(p => if (!Files.exists(p)) Files.createDirectories(p) else ())

  def watch(state0: State, action: State => Task[State]): Task[State] = {
    def runAction(state: State, event: DirectoryChangeEvent): Task[State] = {
      Task(logger.info(s"A ${event.eventType()} in ${event.path()} has triggered an event."))
        .flatMap(_ => action(state))
    }

    val fileEventConsumer = Consumer.foldLeftAsync[State, DirectoryChangeEvent](state0) {
      case (state, event) =>
        event.eventType match {
          case EventType.CREATE => runAction(state, event)
          case EventType.MODIFY => runAction(state, event)
          case EventType.OVERFLOW => runAction(state, event)
          case EventType.DELETE => Task.now(state)
        }
    }

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(
        ExecutionContext.ioScheduler)

    val watcher = DirectoryWatcher.create(
      dirsAsJava,
      new DirectoryChangeListener {
        @volatile var stop: Boolean = false
        override def onEvent(event: DirectoryChangeEvent): Unit = {
          val targetFile = event.path()
          val targetPath = targetFile.toFile.getAbsolutePath()
          if (!stop && Files.isRegularFile(targetFile) &&
              (targetPath.endsWith(".scala") || targetPath.endsWith(".java"))) {
            val ack = observer.onNext(event)
            stop = ack.isCompleted
          }
        }
      }
    )

    val watchingTask = Task {
      logger.info(s"Watching the following directories: ${dirs.mkString(", ")}")
      try watcher.watch()
      finally watcher.close()
    }.doOnCancel(Task(watcher.close()))

    val watchHandle = watchingTask.materialize.runAsync(ExecutionContext.ioScheduler)

    observable
      .consumeWith(fileEventConsumer)
      .executeOn(state0.scheduler)
      .doOnCancel(Task(watchHandle.cancel()))
      .doOnFinish(_ => Task(watchHandle.cancel()))
  }
}
