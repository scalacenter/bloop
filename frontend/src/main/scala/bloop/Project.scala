package bloop

import java.lang.{Boolean => JBoolean}
import java.nio.file.{Files, Paths => NioPaths}
import java.util.Properties

import bloop.exec.JavaEnv
import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.logging.Logger

case class Project(name: String,
                   baseDirectory: AbsolutePath,
                   dependencies: Array[String],
                   scalaInstance: ScalaInstance,
                   classpath: Array[AbsolutePath],
                   classesDir: AbsolutePath,
                   scalacOptions: Array[String],
                   javacOptions: Array[String],
                   sourceDirectories: Array[AbsolutePath],
                   testFrameworks: Array[Array[String]],
                   javaEnv: JavaEnv,
                   tmp: AbsolutePath,
                   bloopConfigDir: AbsolutePath) {
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
    properties.setProperty("scalacOptions", scalacOptions.mkString(";"))
    properties.setProperty("javacOptions", javacOptions.mkString(";"))
    properties.setProperty("sourceDirectories", sourceDirectories.map(_.syntax).mkString(","))
    properties.setProperty("testFrameworks", testFrameworks.map(_.mkString(",")).mkString(";"))
    properties.setProperty("allScalaJars",
                           scalaInstance.allJars.map(_.getAbsolutePath).mkString(","))
    properties.setProperty("tmp", tmp.syntax)
    properties.setProperty("fork", javaEnv.fork.toString)
    properties.setProperty("javaHome", javaEnv.javaHome.syntax)
    properties.setProperty("javaOptions", javaEnv.javaOptions.mkString(";"))
    properties
  }
}

object Project {
  def fromDir(config: AbsolutePath, logger: Logger): List[Project] = {
    timed(logger) {
      // TODO: We're not handling projects with duplicated names here.
      val configFiles = Paths.getAll(config, "glob:**.config")
      logger.info(s"Loading ${configFiles.length} projects from '${config.syntax}'...")
      configFiles.par.map(configFile => fromFile(configFile, logger)).toList
    }
  }

  private def fromFile(config: AbsolutePath, logger: Logger): Project = {
    logger.debug(s"Loading project from '$config'")
    val configFilepath = config.underlying
    val properties = new Properties()
    val inputStream = Files.newInputStream(configFilepath)
    try properties.load(inputStream)
    finally inputStream.close
    fromProperties(properties, config)
  }

  def fromProperties(properties: Properties, config: AbsolutePath): Project = {
    def toPaths(line: String) = line.split(",").map(toPath)
    def toPath(line: String) = AbsolutePath(NioPaths.get(line))
    val name = properties.getProperty("name")
    val baseDirectory = toPath(properties.getProperty("baseDirectory"))
    val dependencies =
      properties.getProperty("dependencies").split(",").filterNot(_.isEmpty)
    val scalaOrganization = properties.getProperty("scalaOrganization")
    val allScalaJars = toPaths(properties.getProperty("allScalaJars"))
    val scalaName = properties.getProperty("scalaName")
    val scalaVersion = properties.getProperty("scalaVersion")
    val scalaInstance = ScalaInstance(scalaOrganization, scalaName, scalaVersion, allScalaJars)
    val classpath = toPaths(properties.getProperty("classpath"))
    val classesDir = toPath(properties.getProperty("classesDir"))
    val scalacOptions =
      properties.getProperty("scalacOptions").split(";").filterNot(_.isEmpty)
    val javacOptions =
      properties.getProperty("javacOptions").split(";").filterNot(_.isEmpty)
    val sourceDirectories = properties
      .getProperty("sourceDirectories")
      .split(",")
      .filterNot(_.isEmpty)
      .map(toPath)
    val testFrameworks =
      properties.getProperty("testFrameworks").split(";").map(_.split(",").filterNot(_.isEmpty))
    val fork = JBoolean.parseBoolean(properties.getProperty("fork"))
    val javaHome = toPath(properties.getProperty("javaHome"))
    val javaOptions = properties.getProperty("javaOptions").split(";").filterNot(_.isEmpty)
    val javaEnv = JavaEnv(fork, javaHome, javaOptions)
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
      testFrameworks,
      javaEnv,
      tmp,
      config
    )
  }
}
