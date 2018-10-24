package bloop.engine

import bloop.data.{Origin, Project}
import bloop.io.Paths.AttributedPath
import bloop.io.AbsolutePath
import bloop.logging.{LogContext, Logger}
import bloop.util.ByteHasher
import monix.eval.Task

object BuildLoader {

  /** The pattern used to find configuration files */
  private[bloop] final val JsonFilePattern: String = "glob:**.json"

  /**
   * Returns all the tracked files inside this directory, associated with their last
   * modification time.
   *
   * @param base     The base file or directory to track.
   * @param pattern  The pattern to find the files to track.
   * @param maxDepth The maximum number of directory levels to visit.
   * @return A map associating each tracked file with its last modification time.
   */
  def readConfigurationFilesInBase(base: AbsolutePath, logger: Logger): List[AttributedPath] = {
    bloop.io.Paths.attributedPathFilesUnder(base, JsonFilePattern, logger, 1)
  }

  /**
   * Load only the projects passed as arguments.
   *
   * @param configRoot The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def loadBuildFromConfigurationFiles(
      configDir: AbsolutePath,
      configFiles: List[Build.ReadConfiguration],
      logger: Logger
  ): Task[List[Project]] = {
    logger.debug(s"Loading ${configFiles.length} projects from '${configDir.syntax}'...")(LogContext.Compilation)
    val all = configFiles.map(f => Task(Project.fromBytesAndOrigin(f.bytes, f.origin, logger)))
    Task.gatherUnordered(all).executeOn(ExecutionContext.scheduler)
  }

  /**
   * Load all the projects from `configDir` in a parallel, lazy fashion via monix Task.
   *
   * @param configDir The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def load(
      configDir: AbsolutePath,
      logger: Logger
  ): Task[List[Project]] = {
    val configFiles = readConfigurationFilesInBase(configDir, logger).map { ap =>
      Task {
        val bytes = ap.path.readAllBytes
        val hash = ByteHasher.hashBytes(bytes)
        Build.ReadConfiguration(Origin(ap, hash), bytes)
      }
    }

    Task
      .gatherUnordered(configFiles)
      .flatMap(fs => loadBuildFromConfigurationFiles(configDir, fs, logger))
  }

  /**
   * Load all the projects from `configDir` synchronously.
   *
   * @param configDir The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def loadSynchronously(
      configDir: AbsolutePath,
      logger: Logger
  ): List[Project] = {
    val configFiles = readConfigurationFilesInBase(configDir, logger).map { ap =>
      val bytes = ap.path.readAllBytes
      val hash = ByteHasher.hashBytes(bytes)
      Build.ReadConfiguration(Origin(ap, hash), bytes)
    }

    logger.debug(s"Loading ${configFiles.length} projects from '${configDir.syntax}'...")(LogContext.Compilation)
    configFiles.map(f => Project.fromBytesAndOrigin(f.bytes, f.origin, logger))
  }
}
