package bloop.data

import bloop.exec.JavaEnv
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import bloop.ScalaInstance
import bloop.bsp.ProjectUris
import bloop.config.{Config, ConfigEncoderDecoders}
import bloop.engine.Dag
import bloop.engine.caches.SemanticDBCache
import bloop.engine.tasks.toolchains.{JvmToolchain, ScalaJsToolchain, ScalaNativeToolchain}
import bloop.io.ByteHasher

import java.nio.charset.StandardCharsets

import scala.util.Try
import scala.collection.mutable

import ch.epfl.scala.{bsp => Bsp}

import xsbti.compile.{ClasspathOptions, CompileOrder}

final case class Project(
    name: String,
    baseDirectory: AbsolutePath,
    workspaceDirectory: Option[AbsolutePath],
    dependencies: List[String],
    scalaInstance: Option[ScalaInstance],
    rawClasspath: List[AbsolutePath],
    resources: List[AbsolutePath],
    compileSetup: Config.CompileSetup,
    genericClassesDir: AbsolutePath,
    scalacOptions: List[String],
    javacOptions: List[String],
    sources: List[AbsolutePath],
    testFrameworks: List[Config.TestFramework],
    testOptions: Config.TestOptions,
    out: AbsolutePath,
    analysisOut: AbsolutePath,
    platform: Platform,
    sbt: Option[Config.Sbt],
    resolution: Option[Config.Resolution],
    origin: Origin
) {

  /** The bsp uri associated with this project. */
  val bspUri: Bsp.Uri = Bsp.Uri(ProjectUris.toURI(baseDirectory, name))

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

  val uniqueId = s"${origin.path.syntax}#${name}"
  override def toString: String = s"$name"
  override val hashCode: Int =
    ByteHasher.hashBytes(uniqueId.getBytes(StandardCharsets.UTF_8))
  override def equals(other: Any): Boolean = {
    other match {
      case other: Project => this.origin.path == other.origin.path && this.name == other.name
      case _ => false
    }
  }

  def pickValidResources: Array[AbsolutePath] = {
    // Only add those resources that exist at the moment of creating the classpath
    resources.iterator.filter(_.exists).toArray
  }

  def fullClasspath(dag: Dag[Project], client: ClientInfo): Array[AbsolutePath] = {
    val addedResources = new mutable.HashSet[AbsolutePath]()
    val selfUniqueClassesDir = client.getUniqueClassesDirFor(this)
    val cp = (selfUniqueClassesDir :: rawClasspath).toBuffer
    // Add the resources right before the classes directory if found in the classpath
    Dag.dfs(dag).foreach { p =>
      val genericClassesDir = p.genericClassesDir
      val uniqueClassesDir = client.getUniqueClassesDirFor(p)
      val index = cp.indexOf(genericClassesDir)
      val newResources = p.pickValidResources.filterNot(r => addedResources.contains(r))
      newResources.foreach(r => addedResources.add(r))
      if (index == -1) {
        // Not found? Weird. Let's add resources to end just in case
        cp.appendAll(newResources)
      } else {
        // Replace in-place for the classes directory unique to the client
        cp(index) = uniqueClassesDir
        // Prepend resources to classes directories
        cp.insertAll(index, newResources)
      }
    }
    cp.toArray
  }

  /**
   * Defines a project-specific path under which Bloop will create all bsp
   * client-owned classes directories. These directories host compile products
   * and their existence and contents are managed by Bloop itself.
   */
  def bspClientClassesRootDirectory: AbsolutePath = {
    genericClassesDir.getParent.resolve("bloop-bsp-clients-classes")
  }
}

object Project {
  final implicit val ps: scalaz.Show[Project] =
    new scalaz.Show[Project] { override def shows(f: Project): String = f.name }

  def defaultPlatform(logger: Logger, javaEnv: Option[JavaEnv] = None): Platform = {
    val platform = Config.Platform.Jvm(Config.JvmConfig.empty, None)
    val env = javaEnv.getOrElse(JavaEnv.fromConfig(platform.config))
    val toolchain = JvmToolchain.resolveToolchain(platform, logger)
    Platform.Jvm(env, toolchain, platform.mainClass)
  }

  def fromConfig(file: Config.File, origin: Origin, logger: Logger): Project = {
    val ec = bloop.engine.ExecutionContext.ioScheduler
    val project = file.project
    val scala = project.`scala`

    // Use the default Bloop scala instance if it's not a Scala project or if Scala jars are empty
    val instance = scala
      .flatMap { scala =>
        if (scala.jars.isEmpty) None
        else {
          val scalaJars = scala.jars.map(AbsolutePath.apply)
          Some(
            ScalaInstance(scala.organization, scala.name, scala.version, scalaJars, logger)(ec)
          )
        }
      }
      .orElse(ScalaInstance.scalaInstanceFromBloop(logger)(ec))

    val setup = project.`scala`.flatMap(_.setup).getOrElse(Config.CompileSetup.empty)
    val platform = project.platform match {
      case Some(platform: Config.Platform.Jvm) =>
        val javaEnv = JavaEnv.fromConfig(platform.config)
        val toolchain = JvmToolchain.resolveToolchain(platform, logger)
        Platform.Jvm(javaEnv, toolchain, platform.mainClass)
      case Some(platform: Config.Platform.Js) =>
        val toolchain = Try(ScalaJsToolchain.resolveToolchain(platform, logger)).toOption
        Platform.Js(platform.config, toolchain, platform.mainClass)
      case Some(platform: Config.Platform.Native) =>
        val toolchain = Try(ScalaNativeToolchain.resolveToolchain(platform, logger)).toOption
        Platform.Native(platform.config, toolchain, platform.mainClass)
      case None => defaultPlatform(logger)
    }

    val sbt = project.sbt
    val resolution = project.resolution

    val out = AbsolutePath(project.out)
    val analysisOut = scala
      .flatMap(_.analysis.map(AbsolutePath.apply))
      .getOrElse(out.resolve(Config.Project.analysisFileName(project.name)))
    val resources = project.resources.toList.flatten.map(AbsolutePath.apply)

    Project(
      project.name,
      AbsolutePath(project.directory),
      project.workspaceDir.map(AbsolutePath.apply),
      project.dependencies,
      instance,
      project.classpath.map(AbsolutePath.apply),
      resources,
      setup,
      AbsolutePath(project.classesDir),
      scala.map(_.options).getOrElse(Nil),
      project.java.map(_.options).getOrElse(Nil),
      project.sources.map(AbsolutePath.apply),
      project.test.map(_.frameworks).getOrElse(Nil),
      project.test.map(_.options).getOrElse(Config.TestOptions.empty),
      AbsolutePath(project.out),
      analysisOut,
      platform,
      sbt,
      resolution,
      origin
    )
  }

  def fromBytesAndOrigin(bytes: Array[Byte], origin: Origin, logger: Logger): Project = {
    import _root_.io.circe.parser
    logger.debug(s"Loading project from '${origin.path}'")(DebugFilter.All)
    val contents = new String(bytes, StandardCharsets.UTF_8)
    parser.parse(contents) match {
      case Left(failure) => throw failure
      case Right(json) =>
        ConfigEncoderDecoders.allDecoder.decodeJson(json) match {
          case Right(file) => Project.fromConfig(file, origin, logger)
          case Left(failure) => throw failure
        }
    }
  }

  def pprint(projects: Traversable[Project]): String = {
    projects.map(p => s"'${p.name}'").mkString(", ")
  }

  /**
   * Enable any Metals-specific setting in a project by applying an in-memory
   * project transformation. A setting is Metals-specific if it's required for
   * Metals to provide a complete IDE experience to users.
   *
   * A side-effect of this transformation is that we force the resolution of the
   * semanticdb plugin. This is an expensive operation that is heavily cached
   * inside [[bloop.engine.caches.SemanticDBCache]] and which can be retried in
   * case the resolution for a version hasn't been successful yet and the
   * workspace settings passed as a parameter asks for another attempt.
   *
   * @param project The project that we want to transform.
   * @param settings The settings that contain Metals-specific information such
   *                 as the expected semanticdb version or supported Scala versions.
   * @param logger The logger responsible of tracking any transformation-related event.
   * @return Either the same project as before or the transformed project.
   */
  def enableMetalsSettings(
      project: Project,
      semanticDBPlugin: Option[AbsolutePath],
      workspaceDir: AbsolutePath,
      logger: Logger
  ): Project = {
    def enableSemanticDB(options: List[String], pluginPath: AbsolutePath): List[String] = {
      val hasSemanticDB = hasSemanticDBEnabledInCompilerOptions(options)
      if (hasSemanticDB) options
      else {
        // TODO: Handle user-configured `targetroot`s inside Bloop's compilation
        // engine so that semanticdb files are replicated in those directories
        val semanticdbScalacOptions = List(
          "-P:semanticdb:failures:warning",
          s"-P:semanticdb:sourceroot:$workspaceDir",
          "-P:semanticdb:synthetics:on",
          "-Xplugin-require:semanticdb",
          s"-Xplugin:$pluginPath"
        )

        (options ++ semanticdbScalacOptions.toList).distinct
      }
    }

    def enableRangePositions(options: List[String]): List[String] = {
      val hasYrangepos = options.exists(_.contains("-Yrangepos"))
      if (hasYrangepos) options else options :+ "-Yrangepos"
    }

    val projectWithRangePositions =
      project.copy(scalacOptions = enableRangePositions(project.scalacOptions))
    semanticDBPlugin match {
      case None => projectWithRangePositions
      case Some(pluginPath) =>
        // Recognize 2.12.8-abdcddd as supported if 2.12.8 exists in supported versions
        val options = projectWithRangePositions.scalacOptions
        val optionsWithSemanticDB = enableSemanticDB(options, pluginPath)
        projectWithRangePositions.copy(scalacOptions = optionsWithSemanticDB)
    }
  }

  def hasSemanticDBEnabledInCompilerOptions(options: List[String]): Boolean = {
    options.exists(opt => opt.contains("-Xplugin") && opt.contains("semanticdb-scalac"))
  }
}
