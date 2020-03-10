package bloop.bsp

import bloop.io.AbsolutePath
import bloop.logging.Logger

import scala.sys.process.ProcessLogger
import scala.sys.process.Process

object RefreshProjects {
  def run(workspaceDir: AbsolutePath, command: List[String], logger: Logger): Unit = {
    val exit = Process(command, cwd = Some(workspaceDir.toFile))
      .!(ProcessLogger(logger.info, logger.info))
    if (exit != 0) {
      logger.error(
        s"Refresh projects command '${command.mkString(" ")}' failed with exit code '$exit'"
      )
    }
  }
}
