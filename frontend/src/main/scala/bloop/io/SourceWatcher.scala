package bloop.io

import java.nio.file.Path

import bloop.cli.ExitStatus
import bloop.engine.State
import bloop.logging.Logger

import scala.collection.JavaConverters._
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryWatcher}

final class SourceWatcher(paths0: Seq[Path], logger: Logger) {
  private val paths = paths0.distinct
  private val pathsAsJava: java.util.List[Path] = paths.asJava

  // Create source directories if they don't exist, otherwise the watcher fails.
  import java.nio.file.Files
  paths.foreach(p => if (!Files.exists(p)) Files.createDirectories(p) else ())
  logger.debug(s"Watching the following directories: ${paths.mkString(", ")}")

  def watch(initialState: State, action: State => State): State = {
    var result: State = initialState
    def runAction(event: DirectoryChangeEvent): Unit = {
      logger.debug(s"A ${event.eventType()} in ${event.path()} has triggered an event.")
      result = action(result)
    }

    val watcher = DirectoryWatcher.create(
      pathsAsJava,
      (event: DirectoryChangeEvent) => {
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
    )

    try { watcher.watch(); result } catch {
      case t: Throwable =>
        logger.error("Unexpected error happened when file watching.")
        logger.trace(t)
        result.mergeStatus(ExitStatus.UnexpectedError)
    }
  }
}
