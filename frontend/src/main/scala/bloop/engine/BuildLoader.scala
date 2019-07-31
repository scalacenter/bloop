package bloop.engine

import bloop.data.{Origin, Project}
import bloop.io.Paths.AttributedPath
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import bloop.io.ByteHasher
import bloop.data.WorkspaceSettings
import bloop.data.LoadedProject
import bloop.engine.caches.SemanticDBCache

import monix.eval.{Task, Coeval}
import scala.collection.mutable
import bloop.ScalaInstance

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
    val workspaceFileName = WorkspaceSettings.settingsFileName.toFile.getName()
    bloop.io.Paths
      .attributedPathFilesUnder(base, JsonFilePattern, logger, 1)
      .filterNot(_.path.toFile.getName() == workspaceFileName)
  }

  /**
   * Loads the build incrementally based on the inputs.
   *
   * @param configRoot The base directory from which to load the projects.
   * @param configs The read configurations for added/modified projects.
   * @param inMemoryChanged The projects that require a re-transformation based on settings.
   * @param settingsForLoad The settings to be used to reload the build.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def loadBuildIncrementally(
      configDir: AbsolutePath,
      configs: List[Build.ReadConfiguration],
      inMemoryChanged: List[Build.InvalidatedInMemoryProject],
      settingsForLoad: Option[WorkspaceSettings],
      logger: Logger
  ): Task[List[LoadedProject]] = {
    val incrementalLoadTask = Task {
      val loadMsg = s"Loading ${configs.length} projects from '${configDir.syntax}'..."
      logger.debug(loadMsg)(DebugFilter.All)
      val rawProjects = configs.map(f => Task(loadProject(f.bytes, f.origin, logger)))
      val groupTasks = rawProjects.grouped(10).map(group => Task.gatherUnordered(group)).toList
      val newOrModifiedRawProjects = Task.sequence(groupTasks).map(fp => fp.flatten)

      newOrModifiedRawProjects.flatMap { projects =>
        val projectsRequiringMetalsTransformation = projects ++ inMemoryChanged.collect {
          case Build.InvalidatedInMemoryProject(project, changes)
              if changes.contains(WorkspaceSettings.SemanticDBVersionChange) =>
            project
        }

        settingsForLoad match {
          case None =>
            Task.now(projectsRequiringMetalsTransformation.map(LoadedProject.RawProject(_)))
          case Some(settings) =>
            resolveSemanticDBForProjects(
              projectsRequiringMetalsTransformation,
              configDir,
              settings.semanticDBVersion,
              settings.supportedScalaVersions,
              logger
            ).map { transformedProjects =>
              transformedProjects.map {
                case (project, None) => LoadedProject.RawProject(project)
                case (project, Some(original)) =>
                  LoadedProject.ConfiguredProject(project, original, settings)
              }
            }
        }
      }
    }

    incrementalLoadTask.flatten.executeOn(ExecutionContext.ioScheduler)
  }

  /**
   * Resolves the semanticdb plugin for every project with a different Scala
   * version and then enables the Metals settings on those projects that have
   * changed in this incremental build load.
   *
   * Some of the logic of this method has a duplicate version in
   * `loadSynchronously` that doesn't use `Task` to resolve SemanticDB plugins
   * in parallel and apply the Metals projects. Any change here **must** also
   * be propagated there.
   */
  private def resolveSemanticDBForProjects(
      rawProjects: List[Project],
      configDir: AbsolutePath,
      semanticDBVersion: String,
      supportedScalaVersions: List[String],
      logger: Logger
  ): Task[List[(Project, Option[Project])]] = {
    val projectsWithNoScalaConfig = new mutable.ListBuffer[Project]()
    val groupedProjectsPerScalaVersion = new mutable.HashMap[String, List[Project]]()
    rawProjects.foreach { project =>
      project.scalaInstance match {
        case None => projectsWithNoScalaConfig.+=(project)
        case Some(instance) =>
          val scalaVersion = instance.version
          groupedProjectsPerScalaVersion.get(scalaVersion) match {
            case Some(projects) =>
              groupedProjectsPerScalaVersion.put(scalaVersion, project :: projects)
            case None => groupedProjectsPerScalaVersion.put(scalaVersion, project :: Nil)
          }
      }
    }

    val enableMetalsInProjectsTask = groupedProjectsPerScalaVersion.toList.map {
      case (scalaVersion, projects) =>
        val coeval = tryEnablingSemanticDB(
          projects,
          configDir,
          scalaVersion,
          semanticDBVersion,
          supportedScalaVersions,
          logger
        ) { (plugin: Option[AbsolutePath]) =>
          projects.map(
            p => Project.enableMetalsSettings(p, configDir, plugin, logger) -> Some(p)
          )
        }
        coeval.task
    }

    Task.gatherUnordered(enableMetalsInProjectsTask).map { pps =>
      // Add projects with Metals settings enabled + projects with no scala config at all
      pps.flatten ++ projectsWithNoScalaConfig.toList.map(_ -> None)
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
  ): List[LoadedProject] = {
    val settings = WorkspaceSettings.readFromFile(configDir, logger)
    val configFiles = readConfigurationFilesInBase(configDir, logger).map { ap =>
      val bytes = ap.path.readAllBytes
      val hash = ByteHasher.hashBytes(bytes)
      Build.ReadConfiguration(Origin(ap, hash), bytes)
    }

    logger.debug(s"Loading ${configFiles.length} projects from '${configDir.syntax}'...")(
      DebugFilter.All
    )

    configFiles.map { f =>
      val project = loadProject(f.bytes, f.origin, logger)
      settings match {
        case None => LoadedProject.RawProject(project)
        case Some(settings) =>
          project.scalaInstance match {
            case None => LoadedProject.RawProject(project)
            case Some(instance) =>
              val scalaVersion = instance.version
              val coeval = tryEnablingSemanticDB(
                List(project),
                configDir,
                scalaVersion,
                settings.semanticDBVersion,
                settings.supportedScalaVersions,
                logger
              ) { (plugin: Option[AbsolutePath]) =>
                LoadedProject.ConfiguredProject(
                  Project.enableMetalsSettings(project, configDir, plugin, logger),
                  project,
                  settings
                )
              }

              // Run coeval, we rethrow but note that `tryEnablingSemanticDB` handles errors
              coeval.run match {
                case Left(value) => throw value
                case Right(value) => value
              }
          }
      }
    }
  }

  private def tryEnablingSemanticDB[T](
      projects: List[Project],
      configDir: AbsolutePath,
      scalaVersion: String,
      semanticDBVersion: String,
      supportedScalaVersions: List[String],
      logger: Logger
  )(
      enableMetals: Option[AbsolutePath] => T
  ): Coeval[T] = {
    // Recognize 2.12.8-abdcddd as supported if 2.12.8 exists in supported versions
    val isUnsupportedVersion = !supportedScalaVersions.exists(scalaVersion.startsWith(_))
    if (isUnsupportedVersion) {
      logger.debug(Feedback.skippedUnsupportedScalaMetals(scalaVersion))(DebugFilter.All)
      Coeval.now(enableMetals(None))
    } else {
      Coeval.eval {
        SemanticDBCache.fetchPlugin(scalaVersion, semanticDBVersion, logger) match {
          case Right(path) =>
            logger.debug(Feedback.configuredMetalsProjects(projects))(DebugFilter.All)
            enableMetals(Some(path))
          case Left(cause) =>
            logger.displayWarningToUser(Feedback.failedMetalsConfiguration(scalaVersion, cause))
            enableMetals(None)
        }
      }
    }
  }

  private def loadProject(bytes: Array[Byte], origin: Origin, logger: Logger): Project = {
    Project.fromBytesAndOrigin(bytes, origin, logger)
  }
}
