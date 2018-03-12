package bloop.io

import java.nio.file.Path

import bloop.cli.ExitStatus
import bloop.engine.State
import bloop.logging.Logger

import scala.collection.JavaConverters._
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}

final class SourceWatcher(dirs0: Seq[Path], logger: Logger, debug: Boolean) {
  private val dirs = dirs0.distinct
  private val dirsAsJava: java.util.List[Path] = dirs.asJava

  // Create source directories if they don't exist, otherwise the watcher fails.
  import java.nio.file.Files
  dirs.foreach(p => if (!Files.exists(p)) Files.createDirectories(p) else ())

  def watch(initialState: State, action: State => State): State = {
    logger.debug(s"Watching the following directories: ${dirs.mkString(", ")}")
    var result: State = initialState
    def runAction(event: DirectoryChangeEvent): Unit = {
      val msg = s"A ${event.eventType()} in ${event.path()} has triggered an event."
      if (debug) logger.info(msg) else logger.debug(msg)
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
}
