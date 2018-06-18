package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.util.Try
import bloop.exec.JavaEnv
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger
import xsbti.compile.ClasspathOptions
import _root_.monix.eval.Task
import bloop.bsp.ProjectUris
import config.{Config, ConfigEncoderDecoders}
import config.Config.Platform
import bloop.engine.ExecutionContext
import bloop.engine.tasks.{ScalaJsToolchain, ScalaNativeToolchain}

import ch.epfl.scala.{bsp => Bsp}

final case class Project(
    name: String,
    baseDirectory: AbsolutePath,
    dependencies: Array[String],
    scalaInstance: ScalaInstance,
    rawClasspath: Array[AbsolutePath],
    classpathOptions: ClasspathOptions,
    classesDir: AbsolutePath,
    scalacOptions: Array[String],
    javacOptions: Array[String],
    sources: Array[AbsolutePath],
    testFrameworks: Array[Config.TestFramework],
    testOptions: Config.TestOptions,
    javaEnv: JavaEnv,
    out: AbsolutePath,
    analysisOut: AbsolutePath,
    platform: Platform,
    jsToolchain: Option[ScalaJsToolchain],
    nativeToolchain: Option[ScalaNativeToolchain],
    sbt: Option[Config.Sbt],
    resolution: Option[Config.Resolution]
) {
  override def toString: String = s"$name"
  override val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this)

  /** The bsp uri associated with this project. */
  val bspUri: Bsp.Uri = Bsp.Uri(ProjectUris.toUri(baseDirectory, name))

  /** This project's full classpath (classes directory and raw classpath) */
  val classpath: Array[AbsolutePath] = classesDir +: rawClasspath
}

object Project {
  final implicit val ps: scalaz.Show[Project] = new scalaz.Show[Project] {
    override def shows(f: Project): String = f.name
  }

  /** The pattern used to find configuration files */
  final val loadPattern: String = "glob:**.json"

  /** The maximum number of directory levels to traverse to find configuration files. */
  final val loadDepth: Int = 1

  private def loadAllFiles(configRoot: AbsolutePath): Array[AbsolutePath] =
    Paths.getAllFiles(configRoot, loadPattern, maxDepth = loadDepth)

  /**
   * Load all the projects from `config` in a parallel, lazy fashion via monix Task.
   *
   * @param configRoot The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def lazyLoadFromDir(configRoot: AbsolutePath, logger: Logger): Task[List[Project]] = {
    // TODO: We're not handling projects with duplicated names here.
    val configFiles = loadAllFiles(configRoot)
    logger.debug(s"Loading ${configFiles.length} projects from '${configRoot.syntax}'...")
    val all = configFiles.iterator.map(configFile => Task(fromFile(configFile, logger))).toList
    Task.gatherUnordered(all).executeOn(ExecutionContext.scheduler)
  }

  /**
   * Load all the projects from `config` in an eager fashion.
   *
   * Useful only for testing purposes, it's the counterpart of [[lazyLoadFromDir()]].
   *
   * @param configRoot The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def eagerLoadFromDir(configRoot: AbsolutePath, logger: Logger): List[Project] = {
    val configFiles = loadAllFiles(configRoot)
    logger.debug(s"Loading ${configFiles.length} projects from '${configRoot.syntax}'...")
    configFiles.iterator.map(configFile => fromFile(configFile, logger)).toList
  }

  def fromConfig(file: Config.File, logger: Logger): Project = {
    val project = file.project
    val scala = project.`scala`
    val isEmpty = scala.organization.isEmpty && scala.name.isEmpty && scala.version.isEmpty && scala.jars.isEmpty

    // Use the default Bloop scala instance if it's not a Scala project
    val instance = {
      if (isEmpty) ScalaInstance.bloopScalaInstance(logger)
      else {
        val scalaJars = scala.jars.map(AbsolutePath.apply).toArray
        ScalaInstance(scala.organization, scala.name, scala.version, scalaJars, logger)
      }
    }

    val classpathOptions = {
      val setup = project.compileSetup
      ClasspathOptions.of(
        setup.addLibraryToBootClasspath,
        setup.addCompilerToClasspath,
        setup.addExtraJarsToClasspath,
        setup.manageBootClasspath,
        setup.filterLibraryFromClasspath
      )
    }

    val jsToolchain = project.platform match {
      case platform: Config.Platform.Js =>
        Try(ScalaJsToolchain.resolveToolchain(platform, logger)).toOption
      case _ => None
    }

    val nativeToolchain = project.platform match {
      case platform: Config.Platform.Native =>
        Try(ScalaNativeToolchain.resolveToolchain(platform, logger)).toOption
      case _ => None
    }

    val javaEnv = project.platform match {
      case Config.Platform.Jvm(Config.JvmConfig(home, jvmOptions), _) =>
        val jvmHome = home.map(AbsolutePath.apply).getOrElse(JavaEnv.DefaultJavaHome)
        JavaEnv(jvmHome, jvmOptions.toArray)
      case _ => JavaEnv.default
    }

    val sbt = project.sbt match {
      case Config.Sbt.empty => None
      case sbt => Some(sbt)
    }

    val resolution = project.resolution match {
      case Config.Resolution.empty => None
      case res => Some(res)
    }

    Project(
      project.name,
      AbsolutePath(project.directory),
      project.dependencies,
      instance,
      project.classpath.map(AbsolutePath.apply),
      classpathOptions,
      AbsolutePath(project.classesDir),
      scala.options,
      project.java.options,
      project.sources.map(AbsolutePath.apply),
      project.test.frameworks,
      project.test.options,
      javaEnv,
      AbsolutePath(project.out),
      AbsolutePath(project.analysisOut),
      project.platform,
      jsToolchain,
      nativeToolchain,
      sbt,
      resolution
    )
  }

  def fromFile(config: AbsolutePath, logger: Logger): Project = {
    import _root_.io.circe.parser
    logger.debug(s"Loading project from '$config'")
    val contents = new String(Files.readAllBytes(config.underlying), StandardCharsets.UTF_8)
    parser.parse(contents) match {
      case Left(failure) => throw failure
      case Right(json) => ConfigEncoderDecoders.allDecoder.decodeJson(json) match {
        case Right(file) => Project.fromConfig(file, logger)
        case Left(failure) => throw failure
      }
    }
  }
}
