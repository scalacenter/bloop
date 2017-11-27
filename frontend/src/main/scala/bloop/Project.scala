package bloop

import java.nio.file.{Files, Paths => NioPaths}
import java.util.{Optional, Properties}

import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.logging.Logger
import sbt.internal.inc.FileAnalysisStore
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

case class Project(name: String,
                   dependencies: Array[String],
                   scalaInstance: ScalaInstance,
                   classpath: Array[AbsolutePath],
                   classesDir: AbsolutePath,
                   scalacOptions: Array[String],
                   javacOptions: Array[String],
                   sourceDirectories: Array[AbsolutePath],
                   previousResult: PreviousResult,
                   tmp: AbsolutePath,
                   origin: Option[AbsolutePath]) {
  def toProperties(): Properties = {
    val properties = new Properties()
    properties.setProperty("name", name)
    properties.setProperty("dependencies", dependencies.mkString(","))
    properties.setProperty("scalaOrg", scalaInstance.organization)
    properties.setProperty("scalaName", scalaInstance.name)
    properties.setProperty("scalaVersion", scalaInstance.version)
    properties.setProperty("classpath", classpath.map(_.syntax).mkString(","))
    properties.setProperty("classesDir", classesDir.syntax)
    properties.setProperty("scalacOptions", scalacOptions.mkString(","))
    properties.setProperty("javacOptions", javacOptions.mkString(","))
    properties.setProperty("sourceDirectories", sourceDirectories.map(_.syntax).mkString(","))
    properties.setProperty("allScalaJars",
                           scalaInstance.allJars.map(_.getAbsolutePath).mkString(","))
    properties.setProperty("tmp", tmp.syntax)
    properties
  }
}

object Project {
  private def createResult(analysis: CompileAnalysis, setup: MiniSetup): PreviousResult =
    PreviousResult.of(Optional.of(analysis), Optional.of(setup))
  private val emptyResult: PreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def fromDir(config: AbsolutePath, logger: Logger): Map[String, Project] = timed(logger) {
    val configFiles = Paths.getAll(config, "glob:**.config").zipWithIndex
    logger.info(s"Loading ${configFiles.length} projects from '${config.syntax}'...")

    val projects = new Array[(String, Project)](configFiles.length)
    configFiles.par.foreach {
      case (file, idx) =>
        val project = fromFile(file)
        projects(idx) = project.name -> project
    }
    projects.toMap
  }

  def fromFile(config: AbsolutePath): Project = {
    val configFilepath = config.underlying
    val inputStream = Files.newInputStream(configFilepath)
    val properties = new Properties()
    properties.load(inputStream)
    val project = fromProperties(properties)
    val previousResult = {
      val analysisFile =
        configFilepath.getParent.resolve(s"${project.name}-analysis.bin")
      if (Files.exists(analysisFile)) {
        FileAnalysisStore
          .binary(analysisFile.toFile)
          .get()
          .map[PreviousResult](a => createResult(a.getAnalysis, a.getMiniSetup))
          .orElseGet(() => emptyResult)
      } else emptyResult
    }
    project.copy(previousResult = previousResult, origin = Some(config))
  }

  def fromProperties(properties: Properties): Project = {
    def toPaths(line: String) = line.split(",").map(NioPaths.get(_)).map(AbsolutePath.apply).toArray
    val name = properties.getProperty("name")
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
    val tmp = AbsolutePath(NioPaths.get(properties.getProperty("tmp")))
    Project(name,
            dependencies,
            scalaInstance,
            classpath,
            classesDir,
            scalacOptions,
            javacOptions,
            sourceDirectories,
            previousResult,
            tmp,
            None)
  }
}
