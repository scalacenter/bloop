package bloop.engine

import bloop.data.JavaSemanticdbSettings
import bloop.data.LoadedProject
import bloop.data.Origin
import bloop.data.Project
import bloop.data.ScalaSemanticdbSettings
import bloop.data.SemanticdbSettings
import bloop.data.WorkspaceSettings
import bloop.engine.caches.SemanticDBCache
import bloop.io.AbsolutePath
import bloop.io.ByteHasher
import bloop.io.Paths.AttributedPath
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task

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
      .attributedPathFilesUnder(base, JsonFilePattern, logger, 1, ignoreVisitErrors = true)
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

        settingsForLoad.flatMap(_.withSemanticdbSettings) match {
          case None =>
            Task.now(projectsRequiringMetalsTransformation.map(LoadedProject.RawProject(_)))
          case Some((settings, semanticdb)) =>
            resolveSemanticDBForProjects(
              projectsRequiringMetalsTransformation,
              configDir,
              semanticdb.javaSemanticdbSettings,
              semanticdb.scalaSemanticdbSettings,
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
      javaSemanticSettings: Option[JavaSemanticdbSettings],
      scalaSemanticdbSettings: Option[ScalaSemanticdbSettings],
      logger: Logger
  ): Task[List[(Project, Option[Project])]] = {

    // java only projects are keyed on None
    val projectsByScalaVersion = rawProjects.groupBy(_.scalaInstance.map(_.version))

    val enableMetalsInProjectsTask = projectsByScalaVersion.toList.map {
      case (scalaVersionOpt, projects) =>
        Task {
          tryEnablingSemanticDB(
            projects,
            javaSemanticSettings,
            scalaVersionOpt.flatMap(scalaVersion =>
              scalaSemanticdbSettings.map(f => (scalaVersion, f))
            ),
            logger
          ) { (scalaPlugin: Option[AbsolutePath], javaPlugin: Option[AbsolutePath]) =>
            projects.map(p =>
              Project.enableMetalsSettings(p, configDir, scalaPlugin, javaPlugin, logger) -> Some(p)
            )
          }
        }
    }

    Task.gatherUnordered(enableMetalsInProjectsTask).map(_.flatten)
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
      settings.flatMap(_.withSemanticdbSettings) match {
        case None => LoadedProject.RawProject(project)
        case Some((settings, semanticdb: SemanticdbSettings)) =>
          val scalaSemanticdbVersionAndSettings = for {
            version <- project.scalaInstance.map(_.version)
            settings <- semanticdb.scalaSemanticdbSettings
          } yield (version, settings)
          tryEnablingSemanticDB(
            List(project),
            semanticdb.javaSemanticdbSettings,
            scalaSemanticdbVersionAndSettings,
            logger
          ) { (scalaPlugin: Option[AbsolutePath], javaPlugin: Option[AbsolutePath]) =>
            LoadedProject.ConfiguredProject(
              Project.enableMetalsSettings(project, configDir, scalaPlugin, javaPlugin, logger),
              project,
              settings
            )
          }
      }
    }
  }

  private def tryEnablingJavaSemanticDB(
      projects: List[Project],
      javaSemanticSettings: JavaSemanticdbSettings,
      logger: Logger
  ): Option[AbsolutePath] = {
    SemanticDBCache.fetchJavaPlugin(javaSemanticSettings.semanticDBVersion, logger) match {
      case Right(path) =>
        logger.debug(Feedback.configuredMetalsJavaProjects(projects))(DebugFilter.All)
        Some(path)
      case Left(cause) =>
        logger.displayWarningToUser(Feedback.failedMetalsJavaConfiguration(cause))
        None
    }
  }
  private def tryEnablingScalaSemanticDB(
      projects: List[Project],
      scalaSemanticdbVersionAndSettings: (String, ScalaSemanticdbSettings),
      logger: Logger
  ): Option[AbsolutePath] = {
    val (scalaVersion, scalaSemanticdbSettings) = scalaSemanticdbVersionAndSettings
    // Recognize 2.12.8-abdcddd as supported if 2.12.8 exists in supported versions
    val isSupportedVersion =
      scalaSemanticdbSettings.supportedScalaVersions.exists(scalaVersion.startsWith(_))
    if (scalaVersion.startsWith("3.")) {
      None
    } else {
      SemanticDBCache.fetchScalaPlugin(
        scalaVersion,
        scalaSemanticdbSettings.semanticDBVersion,
        logger
      ) match {
        case Right(path) =>
          logger.debug(Feedback.configuredMetalsScalaProjects(projects))(DebugFilter.All)
          Some(path)
        case Left(cause) if isSupportedVersion =>
          logger.displayWarningToUser(Feedback.failedMetalsScalaConfiguration(scalaVersion, cause))
          None

        // We try to download anyway, but don't print the exception
        case _ =>
          None
      }
    }
  }

  private def tryEnablingSemanticDB[T](
      projects: List[Project],
      javaSemanticSettings: Option[JavaSemanticdbSettings],
      scalaSemanticdbVersionAndSettings: Option[(String, ScalaSemanticdbSettings)],
      logger: Logger
  )(
      enableMetals: (Option[AbsolutePath], Option[AbsolutePath]) => T
  ): T = {
    val javaSemanticdbPath =
      javaSemanticSettings.flatMap(tryEnablingJavaSemanticDB(projects, _, logger))
    val scalaSemanticdbPath =
      scalaSemanticdbVersionAndSettings.flatMap(tryEnablingScalaSemanticDB(projects, _, logger))
    enableMetals(scalaSemanticdbPath, javaSemanticdbPath)
  }

  private def loadProject(bytes: Array[Byte], origin: Origin, logger: Logger): Project = {
    Project.fromBytesAndOrigin(bytes, origin, logger)
  }
}
