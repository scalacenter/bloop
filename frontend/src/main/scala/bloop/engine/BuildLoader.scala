package bloop.engine

import bloop.data.{Origin, Project}
import bloop.io.Paths.AttributedPath
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import bloop.io.ByteHasher
import monix.eval.Task
import bloop.data.WorkspaceSettings
import bloop.data.LoadedBuild

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
   * Load only the projects passed as arguments.
   *
   * @param configRoot The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def loadBuildFromConfigurationFiles(
      configDir: AbsolutePath,
      configFiles: List[Build.ReadConfiguration],
      incomingSettings: Option[WorkspaceSettings],
      logger: Logger
  ): Task[LoadedBuild] = {
    val workspaceSettings = Task(updateWorkspaceSettings(configDir, logger, incomingSettings))
    logger.debug(s"Loading ${configFiles.length} projects from '${configDir.syntax}'...")(
      DebugFilter.Compilation
    )
    workspaceSettings
      .flatMap { settings =>
        val all = configFiles.map(f => Task(loadProject(f.bytes, f.origin, logger, settings)))
        val groupTasks = all.grouped(10).map(group => Task.gatherUnordered(group)).toList
        Task
          .sequence(groupTasks)
          .map(fp => LoadedBuild(fp.flatten, settings))
      }
      .executeOn(ExecutionContext.ioScheduler)
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
      incomingSettings: Option[WorkspaceSettings],
      logger: Logger
  ): Task[LoadedBuild] = {
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
        loadBuildFromConfigurationFiles(configDir, fs, incomingSettings, logger)
      }
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
  ): LoadedBuild = {
    val settings = WorkspaceSettings.fromFile(configDir, logger)
    val configFiles = readConfigurationFilesInBase(configDir, logger).map { ap =>
      val bytes = ap.path.readAllBytes
      val hash = ByteHasher.hashBytes(bytes)
      Build.ReadConfiguration(Origin(ap, hash), bytes)
    }

    logger.debug(s"Loading ${configFiles.length} projects from '${configDir.syntax}'...")(
      DebugFilter.Compilation
    )
    LoadedBuild(configFiles.map(f => loadProject(f.bytes, f.origin, logger, settings)), settings)
  }

  private def loadProject(
      bytes: Array[Byte],
      origin: Origin,
      logger: Logger,
      settings: Option[WorkspaceSettings]
  ): Project = {
    val project = Project.fromBytesAndOrigin(bytes, origin, logger)
    settings.map(applySettings(_, project, logger)).getOrElse(project)
  }

  private def updateWorkspaceSettings(
      configDir: AbsolutePath,
      logger: Logger,
      incomingSettings: Option[WorkspaceSettings]
  ): Option[WorkspaceSettings] = {
    val savedSettings = WorkspaceSettings.fromFile(configDir, logger)
    incomingSettings match {
      case Some(incoming) =>
        if (savedSettings.isEmpty || savedSettings.exists(_ != incoming)) {
          WorkspaceSettings.write(configDir, incoming)
          Some(incoming)
        } else {
          savedSettings
        }
      case None =>
        savedSettings
    }

  }

  /**
   * Applies workspace settings from bloop.settings.json file to a project. This includes:
   * - SemanticDB plugin version to resolve and include in Scala compiler options
   */
  private def applySettings(
      settings: WorkspaceSettings,
      project: Project,
      logger: Logger
  ): Project = {
    def addSemanticDBOptions(pluginPath: AbsolutePath) = {
      {
        val optionsSet = project.scalacOptions
        val containsSemanticDB = optionsSet.find(
          setting => setting.contains("-Xplugin") && setting.contains("semanticdb-scalac")
        )
        val containsYrangepos = optionsSet.find(_.contains("-Yrangepos"))
        val semanticDBAdded = if (containsSemanticDB.isDefined) {
          logger.info(s"SemanticDB plugin already added: ${containsSemanticDB.get}")
          optionsSet
        } else {
          val workspaceDir = project.origin.path.getParent.getParent
          optionsSet ++ Set(
            "-P:semanticdb:failures:warning",
            s"-P:semanticdb:sourceroot:$workspaceDir",
            "-P:semanticdb:synthetics:on",
            "-Xplugin-require:semanticdb",
            s"-Xplugin:$pluginPath"
          )
        }
        if (containsYrangepos.isDefined) {
          semanticDBAdded
        } else {
          semanticDBAdded :+ "-Yrangepos"
        }.distinct
      }
    }

    val mappedProject = for {
      scalaInstance <- project.scalaInstance
      pluginPath <- SemanticDBCache.findSemanticDBPlugin(
        scalaInstance.version,
        settings.semanticDBVersion,
        logger
      )
    } yield {
      val scalacOptions = addSemanticDBOptions(pluginPath)
      project.copy(scalacOptions = scalacOptions)
    }
    mappedProject.getOrElse(project)
  }

}
