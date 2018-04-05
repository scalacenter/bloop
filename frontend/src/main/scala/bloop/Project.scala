package bloop

import bloop.exec.JavaEnv
import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.logging.Logger
import xsbti.compile.ClasspathOptions
import _root_.monix.eval.Task
import bloop.config.{Config, ConfigDecoders}
import metaconfig.{Conf, Configured}
import org.langmeta.inputs.Input

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
    sourceDirectories: Array[AbsolutePath],
    testFrameworks: Array[Config.TestFramework],
    javaEnv: JavaEnv,
    out: AbsolutePath
) {
  override def toString: String = s"$name"

  /** This project's full classpath (classes directory and raw classpath) */
  val classpath: Array[AbsolutePath] = classesDir +: rawClasspath
}

object Project {

  /** The pattern used to find configuration files */
  final val loadPattern: String = "glob:**.json"

  /** The maximum number of directory levels to traverse to find configuration files. */
  final val loadDepth: Int = 1

  private def loadAllFiles(configRoot: AbsolutePath): Array[AbsolutePath] =
    Paths.getAll(configRoot, loadPattern, maxDepth = loadDepth)

  /**
   * Load all the projects from `config` in a parallel, lazy fashion via monix Task.
   *
   * @param configRoot The base directory from which to load the projects.
   * @param logger The logger that collects messages about project loading.
   * @return The list of loaded projects.
   */
  def lazyLoadFromDir(configRoot: AbsolutePath, logger: Logger): Task[List[Project]] = {
    timed(logger) {
      // TODO: We're not handling projects with duplicated names here.
      val configFiles = loadAllFiles(configRoot)
      logger.debug(s"Loading ${configFiles.length} projects from '${configRoot.syntax}'...")
      val all = configFiles.iterator.map(configFile => Task(fromFile(configFile, logger))).toList
      Task.gatherUnordered(all)
    }
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
    val scalaJars = scala.jars.map(AbsolutePath.apply).toArray
    val instance = ScalaInstance(scala.organization, scala.name, scala.version, scalaJars, logger)

    val classpathOptions = {
      val opts = project.classpathOptions
      ClasspathOptions.of(
        opts.bootLibrary,
        opts.compiler,
        opts.extra,
        opts.autoBoot,
        opts.filterLibrary
      )
    }

    // Replace `JavaEnv` by `Config.Jvm`?
    val jvm = project.jvm
    val jvmHome = jvm.home.map(AbsolutePath.apply).getOrElse(JavaEnv.DefaultJavaHome)
    val javaEnv = JavaEnv(jvmHome, jvm.options)

    Project(
      project.name,
      AbsolutePath(project.directory),
      project.dependencies.toArray,
      instance,
      project.classpath.map(AbsolutePath.apply).toArray,
      classpathOptions,
      AbsolutePath(project.classesDir),
      scala.options.toArray,
      project.java.options.toArray,
      project.sources.map(AbsolutePath.apply).toArray,
      project.test.frameworks,
      javaEnv,
      AbsolutePath(project.out)
    )
  }

  private[bloop] def fromFile(config: AbsolutePath, logger: Logger): Project = {
    import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser
    logger.debug(s"Loading project from '$config'")
    val configFilepath = config.underlying
    val input = Input.File(configFilepath)
    val configured = Conf.parseInput(input)(typesafeConfigMetaconfigParser)
    ConfigDecoders.allConfigDecoder.read(configured) match {
      case Configured.Ok(file) => Project.fromConfig(file, logger)
      case Configured.NotOk(error) => sys.error(error.toString())
    }
  }
}
