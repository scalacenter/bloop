package bloop.data

import java.net.URI

import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import bloop.ScalaInstance
import bloop.bsp.ProjectUris
import bloop.config.Config
import bloop.engine.Dag
import bloop.engine.caches.SemanticDBCache
import bloop.engine.tasks.toolchains.{JvmToolchain, ScalaJsToolchain, ScalaNativeToolchain}
import bloop.io.ByteHasher
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.PathMatcher
import java.nio.file.Path
import java.nio.file.FileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.FileVisitOption
import java.nio.file.SimpleFileVisitor

import scala.util.Try
import scala.collection.mutable
import ch.epfl.scala.{bsp => Bsp}
import xsbti.compile.{ClasspathOptions, CompileOrder}
import bloop.config.ConfigCodecs

import scala.util.control.NonFatal
import monix.eval.Task

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
    sourcesGlobs: List[SourcesGlobs],
    sourceRoots: Option[List[AbsolutePath]],
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

  def workingDirectory: AbsolutePath = {
    val customWorkingDirectory = platform match {
      case jvm: Platform.Jvm =>
        jvm.config.javaOptions.collectFirst {
          case option if option.startsWith("-Duser.dir=") =>
            AbsolutePath(option.stripPrefix("-Duser.dir="))
        }
      case _ =>
        None
    }
    customWorkingDirectory.getOrElse(baseDirectory)
  }

  /** Returns concatenated list of "sources" and expanded "sourcesGlobs". */
  def allSourceFilesAndDirectories: Task[List[AbsolutePath]] = Task {
    val buf = mutable.ListBuffer.empty[AbsolutePath]
    buf ++= sources
    sourcesGlobs.foreach { glob =>
      if (Files.isDirectory(glob.directory.underlying)) {
        Files.walkFileTree(
          glob.directory.underlying,
          java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          glob.walkDepth,
          new SimpleFileVisitor[Path] {
            override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
              if (glob.matches(file)) {
                buf += AbsolutePath(file)
              }
              FileVisitResult.CONTINUE
            }
          }
        )
      }
    }
    buf.result()
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
    val selfUniqueClassesDir = client.getUniqueClassesDirFor(this, forceGeneration = true)
    val cp = (selfUniqueClassesDir :: rawClasspath).toBuffer
    // Add the resources right before the classes directory if found in the classpath
    Dag.dfs(dag).foreach { p =>
      val genericClassesDir = p.genericClassesDir
      val uniqueClassesDir = client.getUniqueClassesDirFor(p, forceGeneration = true)
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
  def clientClassesRootDirectory: AbsolutePath = {
    this.out.resolve("bloop-bsp-clients-classes")
  }

  def jdkConfig: Option[JdkConfig] = {
    platform match {
      case Platform.Jvm(config, _, _) => Some(config)
      case _ => None
    }
  }
}

object Project {
  private implicit val filter = DebugFilter.All
  final implicit val ps: scalaz.Show[Project] =
    new scalaz.Show[Project] { override def shows(f: Project): String = f.name }

  final class ProjectReadException(msg: String, cause: Throwable)
      extends RuntimeException(msg, cause)

  def fromBytesAndOrigin(bytes: Array[Byte], origin: Origin, logger: Logger): Project = {
    logger.debug(s"Loading project from '${origin.path}'")(DebugFilter.All)
    ConfigCodecs.read(bytes) match {
      case Left(failure) =>
        throw new ProjectReadException(s"Failed to load project from ${origin.path}", failure)
      case Right(file) =>
        try {
          val project = Project.fromConfig(file, origin, logger)
          val skipHydraChanges = !project.scalaInstance.map(_.supportsHydra).getOrElse(false)
          if (skipHydraChanges) project
          else enableHydraSettings(project, logger)
        } catch {
          case NonFatal(failure) =>
            throw new ProjectReadException(s"Failed to load project from ${origin.path}", failure)
        }
    }
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

    val setup = project.`scala`.flatMap(_.setup).getOrElse(Config.CompileSetup.empty)
    val platform = project.platform match {
      case Some(platform: Config.Platform.Jvm) =>
        val javaEnv = JdkConfig.fromConfig(platform.config)
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

    val sourceRoots = project.sourceRoots.map(_.map(AbsolutePath.apply))

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
      SourcesGlobs.fromConfig(project, logger),
      sourceRoots,
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

  def defaultPlatform(logger: Logger, jdkConfig: Option[JdkConfig] = None): Platform = {
    val platform = Config.Platform.Jvm(Config.JvmConfig.empty, None)
    val env = jdkConfig.getOrElse(JdkConfig.fromConfig(platform.config))
    val toolchain = JvmToolchain.resolveToolchain(platform, logger)
    Platform.Jvm(env, toolchain, platform.mainClass)
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
      configDir: AbsolutePath,
      semanticDBPlugin: Option[AbsolutePath],
      logger: Logger
  ): Project = {
    val workspaceDir = project.workspaceDirectory.getOrElse(configDir.getParent)
    val isDotty = project.scalaInstance.exists(_.isDotty)

    def enableSemanticdb(options: List[String], pluginPath: AbsolutePath): List[String] = {
      val baseSemanticdbOptions = List(
        "-P:semanticdb:failures:warning",
        "-P:semanticdb:synthetics:on",
        "-Xplugin-require:semanticdb"
      )
      // TODO: Handle user-configured `targetroot`s inside Bloop's compilation
      // engine so that semanticdb files are replicated in those directories
      val hasSemanticDB = hasSemanticDBEnabledInCompilerOptions(options)
      val pluginOption = if (hasSemanticDB) Nil else List(s"-Xplugin:$pluginPath")
      val baseOptions = s"-P:semanticdb:sourceroot:$workspaceDir" :: options.filterNot(
        isSemanticdbSourceRoot
      )
      (baseOptions ++ baseSemanticdbOptions ++ pluginOption).distinct
    }

    def enableDottySemanticdb(options: List[String]) = {
      val ysemanticdb = if (!options.contains("-Ysemanticdb")) List("-Ysemanticdb") else Nil
      val sourceRoot =
        if (!options.contains("-sourceroot")) List("-sourceroot", workspaceDir.toString()) else Nil
      ysemanticdb ++ sourceRoot
    }

    def enableRangePositions(options: List[String]): List[String] = {
      val hasYrangepos = options.exists(_.contains("-Yrangepos"))
      if (hasYrangepos || isDotty) options else options :+ "-Yrangepos"
    }

    val projectWithRangePositions =
      project.copy(scalacOptions = enableRangePositions(project.scalacOptions))

    val options = projectWithRangePositions.scalacOptions

    semanticDBPlugin match {
      case None =>
        if (isDotty) {
          val optionsWithSemanticDB = enableDottySemanticdb(options)
          projectWithRangePositions.copy(scalacOptions = optionsWithSemanticDB)
        } else {
          projectWithRangePositions
        }
      case Some(pluginPath) =>
        val optionsWithSemanticDB = enableSemanticdb(options, pluginPath)
        projectWithRangePositions.copy(scalacOptions = optionsWithSemanticDB)
    }
  }

  def hasSemanticDBEnabledInCompilerOptions(options: List[String]): Boolean = {
    options.exists(opt => opt.contains("-Xplugin") && opt.contains("semanticdb-scalac"))
  }

  def isSemanticdbSourceRoot(option: String): Boolean = {
    option.contains("semanticdb:sourceroot")
  }

  def enableHydraSettings(project: Project, logger: Logger): Project = {
    val homeDir = AbsolutePath(sys.props("user.home"))
    val workspaceDir = project.workspaceDirectory.getOrElse {
      val assumedWorkspace = project.origin.path.getParent
      logger.debug(s"Missing workspace dir for project ${project.name}, assuming $assumedWorkspace")
      assumedWorkspace
    }

    // Project is unique and derived from project name and ivy project configuration
    val hydraTag = project.name

    val hydraRootBaseDir = workspaceDir.resolve(".hydra").createDirectories
    val hydraBaseDir = hydraRootBaseDir.resolve("bloop").createDirectories
    val hydraStoreDir = hydraBaseDir.resolve(hydraTag)
    val hydraTimingsFile = hydraBaseDir.resolve("timings.csv")
    val hydraPartitionFile = hydraStoreDir.resolve("partition.hydra")
    val hydraMetricsDir = homeDir.resolve(".triplequote").resolve("metrics").createDirectories
    val hydraSourcePartitioner = "auto"
    val hydraSourcepath = project.sources.mkString(java.io.File.pathSeparator)

    val storeOption = ("-YhydraStore", hydraStoreDir.syntax)
    val rootDirOption = ("-YrootDirectory", workspaceDir.syntax)
    val timingsFileOption = ("-YtimingsFile", hydraTimingsFile.syntax)
    val partitionFileOption = ("-YpartitionFile", hydraPartitionFile.syntax)
    val metricsDirOption = ("-YhydraMetricsDirectory", hydraMetricsDir.syntax)
    val hydraTagOption = ("-YhydraTag", hydraTag)
    val sourcepathOption = ("-sourcepath", hydraSourcepath)
    val sourcePartitionerOption = (s"-YsourcePartitioner:$hydraSourcePartitioner", "")

    val hydraCpus = ("-cpus", sys.props.get("bloop.hydra.cpus").getOrElse("2"))
    val allHydraOptions = List(
      storeOption,
      rootDirOption,
      timingsFileOption,
      partitionFileOption,
      metricsDirOption,
      hydraTagOption,
      sourcepathOption,
      sourcePartitionerOption,
      hydraCpus
    )

    val optionsWithConflicts =
      project.scalacOptions.filter(option => allHydraOptions.exists(option == _._1)).toSet
    val newScalacOptionsBuf = new mutable.ListBuffer[String]
    project.scalacOptions.foreach(oldOption => newScalacOptionsBuf.+=(oldOption))
    allHydraOptions.foreach {
      case (key, value) =>
        // Do nothing if there's a conflict with a user-defined setting
        if (optionsWithConflicts.contains(key)) ()
        else newScalacOptionsBuf.+=(key).+=(value)
    }

    val newScalacOptions = newScalacOptionsBuf.toList
    project.copy(scalacOptions = newScalacOptions)
  }
}
