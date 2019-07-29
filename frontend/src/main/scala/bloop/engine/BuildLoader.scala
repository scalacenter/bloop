package bloop.engine

import bloop.data.{Origin, Project}
import bloop.io.Paths.AttributedPath
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import bloop.io.ByteHasher
import bloop.data.WorkspaceSettings
import bloop.data.PartialLoadedBuild
import bloop.data.LoadedProject

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
    bloop.io.Paths
      .attributedPathFilesUnder(base, JsonFilePattern, logger, 1)
      .filterNot(_.path.toFile.getName() == WorkspaceSettings.settingsFileName)
  }

  /**
   * Loads only the projects passed as arguments.
   *
   * @param configRoot The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def loadBuildFromConfigurationFiles(
      configDir: AbsolutePath,
      configFiles: List[Build.ReadConfiguration],
      newSettings: Option[WorkspaceSettings],
      logger: Logger
  ): Task[PartialLoadedBuild] = {
    val workspaceSettings = Task(updateWorkspaceSettings(configDir, logger, newSettings))
    logger.debug(s"Loading ${configFiles.length} projects from '${configDir.syntax}'...")(
      DebugFilter.All
    )

    val loadSettingsAndBuild = workspaceSettings.flatMap { settings =>
      val all = configFiles.map(f => Task(loadProject(f.bytes, f.origin, logger, settings)))
      val groupTasks = all.grouped(10).map(group => Task.gatherUnordered(group)).toList
      Task.sequence(groupTasks).map(fp => PartialLoadedBuild(fp.flatten))
    }

    loadSettingsAndBuild.executeOn(ExecutionContext.ioScheduler)
  }

  /**
   * Load all the projects from `configDir` in a parallel, lazy fashion via monix Task.
   *
   * @param configDir The base directory from which to load the projects.
   * @param newSettings The settings that we should use to load this build.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def load(
      configDir: AbsolutePath,
      newSettings: Option[WorkspaceSettings],
      logger: Logger
  ): Task[PartialLoadedBuild] = {
    val configFiles = readConfigurationFilesInBase(configDir, logger).map { ap =>
      Task {
        val bytes = ap.path.readAllBytes
        val hash = ByteHasher.hashBytes(bytes)
        Build.ReadConfiguration(Origin(ap, hash), bytes)
      }
    }

    Task
      .gatherUnordered(configFiles)
      .flatMap { fs =>
        loadBuildFromConfigurationFiles(configDir, fs, newSettings, logger)
      }
  }

  /**
   * Loads all the projects from `configDir` synchronously.
   *
   * This method does not take any new settings because its call-sites are
   * not used in the CLI/bloop server, instead this is an entrypoint used
   * mostly for our testing and community build infrastructure.
   *
   * @param configDir The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def loadSynchronously(
      configDir: AbsolutePath,
      logger: Logger
  ): PartialLoadedBuild = {
    val settings = WorkspaceSettings.readFromFile(configDir, logger)
    val configFiles = readConfigurationFilesInBase(configDir, logger).map { ap =>
      val bytes = ap.path.readAllBytes
      val hash = ByteHasher.hashBytes(bytes)
      Build.ReadConfiguration(Origin(ap, hash), bytes)
    }

    logger.debug(s"Loading ${configFiles.length} projects from '${configDir.syntax}'...")(
      DebugFilter.All
    )

    PartialLoadedBuild(configFiles.map(f => loadProject(f.bytes, f.origin, logger, settings)))
  }

  private def loadProject(
      bytes: Array[Byte],
      origin: Origin,
      logger: Logger,
      settings: Option[WorkspaceSettings]
  ): LoadedProject = {
    val project = Project.fromBytesAndOrigin(bytes, origin, logger)
    settings match {
      case None => LoadedProject.RawProject(project)
      case Some(settings) =>
        Project.enableMetalsSettings(project, settings, logger) match {
          case Left(project) => LoadedProject.RawProject(project)
          case Right(transformed) => LoadedProject.ConfiguredProject(transformed, project, settings)
        }
    }
  }

  private def updateWorkspaceSettings(
      configDir: AbsolutePath,
      logger: Logger,
      incomingSettings: Option[WorkspaceSettings]
  ): Option[WorkspaceSettings] = {
    val currentSettings = WorkspaceSettings.readFromFile(configDir, logger)
    incomingSettings match {
      case Some(newSettings)
          if currentSettings.isEmpty || currentSettings.exists(_ != newSettings) =>
        WorkspaceSettings.writeToFile(configDir, newSettings).left.foreach { t =>
          logger.debug(s"Unexpected failure when writing workspace settings: $t")(DebugFilter.All)
          logger.trace(t)
        }
        Some(newSettings)
      case _ => currentSettings
    }
  }
}
