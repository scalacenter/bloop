package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.util.Try
import bloop.exec.JavaEnv
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger
import xsbti.compile.{ClasspathOptions, CompileOrder}
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
    dependencies: List[String],
    scalaInstance: Option[ScalaInstance],
    rawClasspath: List[AbsolutePath],
    compileSetup: Config.CompileSetup,
    classesDir: AbsolutePath,
    scalacOptions: List[String],
    javacOptions: List[String],
    sources: List[AbsolutePath],
    testFrameworks: List[Config.TestFramework],
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
  val classpath: Array[AbsolutePath] = (classesDir :: rawClasspath).toArray

  val classpathOptions: ClasspathOptions = {
    ClasspathOptions.of(
      compileSetup.addLibraryToBootClasspath,
      compileSetup.addCompilerToClasspath,
      compileSetup.addExtraJarsToClasspath,
      compileSetup.manageBootClasspath,
      compileSetup.filterLibraryFromClasspath
    )
  }

  val compileOrder: CompileOrder = compileSetup.order match {
    case Config.Mixed => CompileOrder.Mixed
    case Config.JavaThenScala => CompileOrder.JavaThenScala
    case Config.ScalaThenJava => CompileOrder.ScalaThenJava
  }
}

object Project {
  final implicit val ps: scalaz.Show[Project] = new scalaz.Show[Project] {
    override def shows(f: Project): String = f.name
  }

  /** The pattern used to find configuration files */
  final val loadPattern: String = "glob:**.json"

  /** The maximum number of directory levels to traverse to find configuration files. */
  final val loadDepth: Int = 1

  private def loadAllFiles(configRoot: AbsolutePath): List[AbsolutePath] =
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

    // Use the default Bloop scala instance if it's not a Scala project or if Scala jars are empty
    val instance = scala.flatMap { scala =>
      if (scala.jars.isEmpty) None
      else {
        val scalaJars = scala.jars.map(AbsolutePath.apply)
        Some(ScalaInstance(scala.organization, scala.name, scala.version, scalaJars, logger))
      }
    }.orElse(ScalaInstance.scalaInstanceFromBloop(logger))

    val setup = project.`scala`.flatMap(_.setup).getOrElse(Config.CompileSetup.empty)
    val jsToolchain = project.platform.flatMap { platform =>
      Try(ScalaJsToolchain.resolveToolchain(platform, logger)).toOption
    }

    val nativeToolchain = project.platform.flatMap { platform =>
      Try(ScalaNativeToolchain.resolveToolchain(platform, logger)).toOption
    }

    val javaEnv = project.platform match {
      case Some(Config.Platform.Jvm(Config.JvmConfig(home, jvmOptions), _)) =>
        val jvmHome = home.map(AbsolutePath.apply).getOrElse(JavaEnv.DefaultJavaHome)
        JavaEnv(jvmHome, jvmOptions.toArray)
      case _ => JavaEnv.default
    }

    val sbt = project.sbt
    val resolution = project.resolution

    val out = AbsolutePath(project.out)
    val analysisOut = scala
      .flatMap(_.analysis.map(AbsolutePath.apply))
      .getOrElse(out.resolve(Config.Project.analysisFileName(project.name)))
    Project(
      project.name,
      AbsolutePath(project.directory),
      project.dependencies,
      instance,
      project.classpath.map(AbsolutePath.apply),
      setup,
      AbsolutePath(project.classesDir),
      scala.map(_.options).getOrElse(Nil),
      project.java.map(_.options).getOrElse(Nil),
      project.sources.map(AbsolutePath.apply),
      project.test.map(_.frameworks).getOrElse(Nil),
      project.test.map(_.options).getOrElse(Config.TestOptions.empty),
      javaEnv,
      AbsolutePath(project.out),
      analysisOut,
      project.platform.getOrElse(Config.Platform.default),
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
      case Right(json) =>
        ConfigEncoderDecoders.allDecoder.decodeJson(json) match {
          case Right(file) => Project.fromConfig(file, logger)
          case Left(failure) => throw failure
        }
    }
  }
}
