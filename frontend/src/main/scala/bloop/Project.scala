package bloop

import java.nio.file.{Files, Paths => NioPaths}
import java.util.concurrent.ConcurrentHashMap
import java.util.{Optional, Properties}

import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.logging.Logger
import bloop.tasks.CompilationTasks
import sbt.internal.inc.FileAnalysisStore
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

case class Project(name: String,
                   baseDirectory: AbsolutePath,
                   dependencies: Array[String],
                   scalaInstance: ScalaInstance,
                   classpath: Array[AbsolutePath],
                   classesDir: AbsolutePath,
                   scalacOptions: Array[String],
                   javacOptions: Array[String],
                   sourceDirectories: Array[AbsolutePath],
                   previousResult: PreviousResult,
                   testFrameworks: Array[Array[String]],
                   tmp: AbsolutePath,
                   origin: Option[AbsolutePath]) {
  def toProperties(): Properties = {
    val properties = new Properties()
    properties.setProperty("name", name)
    properties.setProperty("baseDirectory", baseDirectory.syntax)
    properties.setProperty("dependencies", dependencies.mkString(","))
    properties.setProperty("scalaOrganization", scalaInstance.organization)
    properties.setProperty("scalaName", scalaInstance.name)
    properties.setProperty("scalaVersion", scalaInstance.version)
    properties.setProperty("classpath", classpath.map(_.syntax).mkString(","))
    properties.setProperty("classesDir", classesDir.syntax)
    properties.setProperty("scalacOptions", scalacOptions.mkString(","))
    properties.setProperty("javacOptions", javacOptions.mkString(","))
    properties.setProperty("sourceDirectories", sourceDirectories.map(_.syntax).mkString(","))
    properties.setProperty("testFrameworks", testFrameworks.map(_.mkString(",")).mkString(";"))
    properties.setProperty("allScalaJars",
                           scalaInstance.allJars.map(_.getAbsolutePath).mkString(","))
    properties.setProperty("tmp", tmp.syntax)
    properties
  }
}

object Project {

  private val cache = new ConcurrentHashMap[AbsolutePath, Map[String, Project]]()

  def update(config: AbsolutePath, projects: Map[String, Project]): Unit = {
    val _ = cache.put(config, projects)
  }

  def persistAllProjects(logger: Logger): Unit = timed(logger) {
    logger.info(s"Persisting incremental compiler state...")
    cache.values().forEach { projectsMap =>
      projectsMap.values.foreach { project =>
        CompilationTasks.persistAnalysis(project, logger)
      }
    }
  }

  private def createResult(analysis: CompileAnalysis, setup: MiniSetup): PreviousResult =
    PreviousResult.of(Optional.of(analysis), Optional.of(setup))
  private val emptyResult: PreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def fromDir(config: AbsolutePath, logger: Logger): Map[String, Project] = timed(logger) {
    cache.computeIfAbsent(config, newProjectsFromDir(_, logger))
  }

  private def newProjectsFromDir(config: AbsolutePath, logger: Logger): Map[String, Project] = {
    val configFiles = Paths.getAll(config, "glob:**.config").zipWithIndex
    logger.info(s"Loading ${configFiles.length} projects from '${config.syntax}'...")

    val projects = new Array[(String, Project)](configFiles.length)
    configFiles.par.foreach {
      case (file, idx) =>
        val project = fromFile(file, logger)
        projects(idx) = project.name -> project
    }
    projects.toMap
  }

  def fromFile(config: AbsolutePath, logger: Logger): Project = {
    logger.debug(s"Loading project from '$config'")
    val configFilepath = config.underlying
    val properties = new Properties()
    val inputStream = Files.newInputStream(configFilepath)
    try properties.load(inputStream)
    finally inputStream.close
    val project = fromProperties(properties)
    val previousResult = {
      val analysisFile =
        configFilepath.getParent.resolve(s"${project.name}-analysis.bin")
      if (Files.exists(analysisFile)) {
        logger.debug(
          s"Loading previous analysis for project '${project.name}' from '$analysisFile'")
        FileAnalysisStore
          .binary(analysisFile.toFile)
          .get()
          .map[PreviousResult](a => createResult(a.getAnalysis, a.getMiniSetup))
          .orElseGet(() => emptyResult)
      } else {
        logger.debug(s"No previous analysis for project '${project.name}'")
        emptyResult
      }
    }
    project.copy(previousResult = previousResult, origin = Some(config))
  }

  def fromProperties(properties: Properties): Project = {
    def toPaths(line: String) = line.split(",").map(NioPaths.get(_)).map(AbsolutePath.apply)
    val name = properties.getProperty("name")
    val baseDirectory = AbsolutePath(NioPaths.get(properties.getProperty("baseDirectory")))
    val dependencies =
      properties.getProperty("dependencies").split(",").filterNot(_.isEmpty)
    val scalaOrganization = properties.getProperty("scalaOrganization")
    val allScalaJars = toPaths(properties.getProperty("allScalaJars"))
    val scalaName = properties.getProperty("scalaName")
    val scalaVersion = properties.getProperty("scalaVersion")
    val scalaInstance = ScalaInstance(scalaOrganization, scalaName, scalaVersion, allScalaJars)
    val classpath = toPaths(properties.getProperty("classpath"))
    val classesDir = AbsolutePath(NioPaths.get(properties.getProperty("classesDir")))
    val scalacOptions =
      properties.getProperty("scalacOptions").split(";").filterNot(_.isEmpty)
    val javacOptions =
      properties.getProperty("javacOptions").split(";").filterNot(_.isEmpty)
    val sourceDirectories = properties
      .getProperty("sourceDirectories")
      .split(",")
      .filterNot(_.isEmpty)
      .map(d => AbsolutePath(NioPaths.get(d)))
    val previousResult =
      PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])
    val testFrameworks =
      properties.getProperty("testFrameworks").split(";").map(_.split(",").filterNot(_.isEmpty))
    val tmp = AbsolutePath(NioPaths.get(properties.getProperty("tmp")))
    Project(
      name,
      baseDirectory,
      dependencies,
      scalaInstance,
      classpath,
      classesDir,
      scalacOptions,
      javacOptions,
      sourceDirectories,
      previousResult,
      testFrameworks,
      tmp,
      None
    )
  }
}
