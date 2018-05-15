package bloop.exec

import bloop.io.AbsolutePath
import bloop.logging.{Logger, ProcessLogger}

import java.nio.file.Files

object Process {

  /**
   * Runs `cmd` in a new process and logs the results. Blocks until the process is finished.
   * The exit code is returned.
   *
   * @param cwd         The directory in which to start the process.
   * @param cmd         The command to run.
   * @param environment The environment properties to run the program with.
   * @param logger      The logger that will receive the output emitted by the process.
   * @return The exit code of the process.
   */
  def run(cwd: AbsolutePath,
          cmd: Seq[String],
          environment: Map[String, String],
          logger: Logger): Int = {
    import scala.collection.JavaConverters.mapAsJavaMapConverter

    if (!Files.exists(cwd.underlying)) {
      logger.error(s"Couldn't start the process because '$cwd' doesn't exist.")
      Forker.EXIT_ERROR
    } else {
      val processBuilder = new ProcessBuilder(cmd: _*)
      processBuilder.directory(cwd.toFile)
      val processEnv = processBuilder.environment()
      processEnv.clear()
      processEnv.putAll(environment.asJava)
      val process = processBuilder.start()
      val processLogger = new ProcessLogger(logger, process)
      processLogger.start()
      val exitCode = process.waitFor()

      logger.debug(s"Process exited with code: $exitCode")
      exitCode
    }
  }

}
