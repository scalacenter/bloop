package bloop

import java.nio.file.{Files, Path, Paths}
import java.util.{Optional, Properties}

import bloop.util.Progress
import sbt.internal.inc.FileAnalysisStore
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

case class Project(name: String,
                   dependencies: Array[String],
                   scalaInstance: ScalaInstance,
                   componentProvider: ComponentProvider,
                   classpath: Array[Path],
                   classesDir: Path,
                   scalacOptions: Array[String],
                   javacOptions: Array[String],
                   sourceDirectories: Array[Path],
                   previousResult: PreviousResult,
                   tmp: Path,
                   origin: Option[Path]) {
  def toProperties(): Properties = {
    val properties = new Properties()
    properties.setProperty("name", name)
    properties.setProperty("dependencies", dependencies.mkString(","))
    properties.setProperty("scalaOrg", scalaInstance.organization)
    properties.setProperty("scalaName", scalaInstance.name)
    properties.setProperty("scalaVersion", scalaInstance.version)
    properties.setProperty("componentProviderBase",
                           componentProvider.baseDir.toAbsolutePath.toString)
    properties.setProperty("classpath", classpath.map(_.toAbsolutePath.toString).mkString(","))
    properties.setProperty("classesDir", classesDir.toAbsolutePath.toString)
    properties.setProperty("scalacOptions", scalacOptions.mkString(","))
    properties.setProperty("javacOptions", javacOptions.mkString(","))
    properties.setProperty("sourceDirectories",
                           sourceDirectories.map(_.toAbsolutePath.toString).mkString(","))
    properties.setProperty("tmp", tmp.toAbsolutePath.toString)
    properties
  }
}

object Project {
  private def createResult(analysis: CompileAnalysis, setup: MiniSetup): PreviousResult =
    PreviousResult.of(Optional.of(analysis), Optional.of(setup))
  private val emptyResult: PreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def fromDir(config: Path): Map[String, Project] = {
    val configFiles = IO.getAll(config, "glob:**.config").zipWithIndex
    println(s"Loading ${configFiles.length} projects from '$config'...")

    val progress = new Progress(configFiles.length)
    val projects = new Array[(String, Project)](configFiles.length)
    configFiles.par.foreach {
      case (file, idx) =>
        val project = fromFile(file)
        projects(idx) = project.name -> project
        progress.update()
    }
    projects.toMap
  }

  def fromFile(config: Path): Project = {
    val inputStream = Files.newInputStream(config)
    val properties  = new Properties()
    properties.load(inputStream)
    val project = fromProperties(properties)
    val previousResult = {
      val analysisFile =
        config.getParent.resolve(s"${project.name}-analysis.bin")
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
    val name = properties.getProperty("name")
    val dependencies =
      properties.getProperty("dependencies").split(",").filterNot(_.isEmpty)
    val scalaOrganization = properties.getProperty("scalaOrganization")
    val scalaName         = properties.getProperty("scalaName")
    val scalaVersion      = properties.getProperty("scalaVersion")
    val scalaInstance =
      ScalaInstance(scalaOrganization, scalaName, scalaVersion)
    val componentProvider = new ComponentProvider(
      Paths.get(properties.getProperty("componentProviderBase")))
    val classpath =
      properties.getProperty("classpath").split(",").map(Paths.get(_))
    val classesDir = Paths.get(properties.getProperty("classesDir"))
    val scalacOptions =
      properties.getProperty("scalacOptions").split(",").filterNot(_.isEmpty)
    val javacOptions =
      properties.getProperty("javacOptions").split(",").filterNot(_.isEmpty)
    val sourceDirectories = properties
      .getProperty("sourceDirectories")
      .split(",")
      .filterNot(_.isEmpty)
      .map(Paths.get(_))
    val previousResult =
      PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])
    val tmp = Paths.get(properties.getProperty("tmp"))
    Project(name,
            dependencies,
            scalaInstance,
            componentProvider,
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
