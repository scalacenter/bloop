package bloop.data

import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.util.Properties
import scala.util.Try
import scala.util.control.NonFatal

import ch.epfl.scala.{bsp => Bsp}

import bloop.ScalaInstance
import bloop.bsp.ProjectUris
import bloop.config.Config
import bloop.config.ConfigCodecs
import bloop.engine.Dag
import bloop.engine.SourceGenerator
import bloop.engine.tasks.toolchains.JvmToolchain
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.engine.tasks.toolchains.ScalaNativeToolchain
import bloop.io.AbsolutePath
import bloop.io.ByteHasher
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import scalaz.Cord
import xsbti.compile.ClasspathOptions
import xsbti.compile.CompileOrder

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
    sourceGenerators: List[SourceGenerator],
    testFrameworks: List[Config.TestFramework],
    testOptions: Config.TestOptions,
    out: AbsolutePath,
    analysisOut: AbsolutePath,
    platform: Platform,
    sbt: Option[Config.Sbt],
    resolution: Option[Config.Resolution],
    tags: List[String],
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
        jvm.config.javaOptions.reverseIterator.collectFirst {
          case option if option.startsWith("-Duser.dir=") =>
            AbsolutePath(option.stripPrefix("-Duser.dir="))
        }
      case _ =>
        None
    }
    customWorkingDirectory.getOrElse(baseDirectory)
  }

  def allGeneratorInputs: Task[List[AbsolutePath]] =
    Task.sequence(sourceGenerators.map(_.getSources)).map(_.flatten)

  /** Returns concatenated list of "sources" and expanded "sourcesGlobs". */
  def allUnmanagedSourceFilesAndDirectories: Task[List[AbsolutePath]] = Task {
    val buf = mutable.ListBuffer.empty[AbsolutePath]
    buf ++= sources
    for (glob <- sourcesGlobs) glob.walkThrough(buf += _)
    buf.result()
  }

  val uniqueId: String = s"${origin.path.syntax}#${name}"
  override def toString: String = s"$name"
  override val hashCode: Int =
    ByteHasher.hashBytes(uniqueId.getBytes(StandardCharsets.UTF_8))
  override def equals(other: Any): Boolean = {
    other match {
      case other: Project => this.origin.path == other.origin.path && this.name == other.name
      case _ => false
    }
  }

  def runtimeResources: List[AbsolutePath] = platform match {
    case jvm: Platform.Jvm => jvm.resources
    case _ => resources
  }

  private def fullClasspath(
      dag: Dag[Project],
      client: ClientInfo,
      rawClasspath: List[AbsolutePath],
      pickValidResources: Project => Array[AbsolutePath]
  ): Array[AbsolutePath] = {
    val addedResources = new mutable.HashSet[AbsolutePath]()
    val cp = (this.genericClassesDir :: rawClasspath).toBuffer

    // Add the resources right before the classes directory if found in the classpath
    Dag.dfs(dag, mode = Dag.PreOrder).foreach { p =>
      val genericClassesDir = p.genericClassesDir
      val uniqueClassesDir = client.getUniqueClassesDirFor(p, forceGeneration = true)
      val index = cp.indexOf(genericClassesDir)
      val newResources = pickValidResources(p).filterNot(r => addedResources.contains(r))
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

  def fullClasspath(dag: Dag[Project], client: ClientInfo): Array[AbsolutePath] = {
    fullClasspath(dag, client, rawClasspath, p => Project.pickValidResources(p.resources))
  }

  def fullRuntimeClasspath(dag: Dag[Project], client: ClientInfo): Array[AbsolutePath] = {
    val rawRuntimeClasspath = platform match {
      case jvm: Platform.Jvm => jvm.classpath
      case _ => rawClasspath
    }
    fullClasspath(
      dag,
      client,
      rawRuntimeClasspath,
      p => Project.pickValidResources(p.runtimeResources)
    )
  }

  /**
   * Defines a project-specific path under which Bloop will create all bsp
   * client-owned classes directories. These directories host compile products
   * and their existence and contents are managed by Bloop itself.
   */
  def clientClassesRootDirectory: AbsolutePath = {
    this.out.resolve("bloop-bsp-clients-classes")
  }

  def compileJdkConfig: Option[JdkConfig] = {
    platform match {
      case jvm: Platform.Jvm => Some(jvm.config)
      case _ => None
    }
  }

  def runtimeJdkConfig: Option[JdkConfig] = {
    platform match {
      case jvm: Platform.Jvm => jvm.runtimeConfig.orElse(compileJdkConfig)
      case _ => compileJdkConfig
    }
  }

  def javaVersionAtLeast(version: String, logger: Logger): Boolean = {

    def compareVersions(a: String, b: String): Int = {
      def versionOf(s: String, depth: Int): (Int, String) =
        s.indexOf('.') match {
          case 0 =>
            (-2, s.substring(1))
          case 1 if depth == 0 && s.charAt(0) == '1' =>
            val r0 = s.substring(2)
            val (v, r) = versionOf(r0, 1)
            val n = if (v > 8 || r0.isEmpty) -2 else v // accept 1.8, not 1.9 or 1.
            (n, r)
          case -1 =>
            val n = if (!s.isEmpty) s.toInt else if (depth == 0) -2 else 0
            (n, "")
          case i =>
            val r = s.substring(i + 1)
            val n = if (depth < 2 && r.isEmpty) -2 else s.substring(0, i).toInt
            (n, r)
        }
      def compareVersions(s: String, v: String, depth: Int): Int = {
        if (depth >= 3) 0
        else {
          val (sn, srest) = versionOf(s, depth)
          val (vn, vrest) = versionOf(v, depth)
          if (vn < 0) -2
          else if (sn < 0) -2
          else if (sn < vn) -1
          else if (sn > vn) 1
          else compareVersions(srest, vrest, depth + 1)
        }
      }
      compareVersions(a, b, 0) match {
        case -2 => throw new NumberFormatException(s"Not a version: $version")
        case i => i
      }
    }
    def getJavaVersionFromJavaHome(javaHome: AbsolutePath): String = {
      val releaseFile = javaHome.resolve("release")
      def rtJar = javaHome.resolve("lib").resolve("rt.jar")
      if (releaseFile.exists) {
        val properties = ConfigFactory.parseFile(
          releaseFile.toFile,
          ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
        )
        try properties.getString("JAVA_VERSION").stripPrefix("\"").stripSuffix("\"")
        catch {
          case _: ConfigException =>
            logger.error(
              s"$javaHome release file missing JAVA_VERSION property - using Bloop's JVM version ${Properties.javaVersion}"
            )
            Properties.javaVersion
        }
      } else if (rtJar.exists) {
        // jdk 8 doesn't have `release` file
        "1.8.0"
      } else {
        logger.error(
          s"No `release` file found in $javaHome - using Bloop's JVM version ${Properties.javaVersion}"
        )
        Properties.javaVersion
      }
    }

    val compileVersion = compileJdkConfig
      .map(f =>
        if (f.javaHome == AbsolutePath(Properties.javaHome))
          Properties.javaVersion
        else
          getJavaVersionFromJavaHome(f.javaHome)
      )
      .getOrElse(Properties.javaVersion)
      .split("-")
      .head // needed for versions like 17-ea
    try {
      compareVersions(compileVersion, version) >= 0
    } catch {
      case NonFatal(_) =>
        logger.error(s"Invalid Java number $compileVersion")
        false
    }
  }
}

object Project {
  private implicit val filter: DebugFilter.All.type = DebugFilter.All
  final implicit val ps: scalaz.Show[Project] =
    new scalaz.Show[Project] {
      override def shows(f: Project): String = f.name
      override def show(f: Project): Cord = Cord(shows(f))
    }

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
    val project = file.project
    val scala = project.`scala`

    // Use the default Bloop scala instance if it's not a Scala project or if Scala jars are empty
    val instance = scala
      .flatMap { scala =>
        if (scala.jars.isEmpty) None
        else {
          val scalaJars = scala.jars.map(AbsolutePath.apply)
          Some(
            ScalaInstance(scala.organization, scala.name, scala.version, scalaJars, logger)
          )
        }
      }

    val setup = project.`scala`.flatMap(_.setup).getOrElse(Config.CompileSetup.empty)
    val compileClasspath = project.classpath.map(AbsolutePath.apply)
    val compileResources = project.resources.toList.flatten.map(AbsolutePath.apply)
    val platform = project.platform match {
      case Some(platform: Config.Platform.Jvm) =>
        val compileEnv = JdkConfig.fromConfig(platform.config)
        val runtimeEnv = platform.runtimeConfig.map(JdkConfig.fromConfig)
        val toolchain = JvmToolchain.resolveToolchain(platform, logger)
        val runtimeClasspath = platform.classpath
          .map(_.map(AbsolutePath.apply))
          .getOrElse(compileClasspath)
        val runtimeResources = platform.resources
          .map(_.map(AbsolutePath.apply))
          .getOrElse(compileResources)

        Platform.Jvm(
          compileEnv,
          toolchain,
          platform.mainClass,
          runtimeEnv,
          runtimeClasspath,
          runtimeResources
        )

      case Some(platform: Config.Platform.Js) =>
        val toolchain = Try(ScalaJsToolchain.resolveToolchain(platform, logger)).toOption
        Platform.Js(platform.config, toolchain, platform.mainClass)

      case Some(platform: Config.Platform.Native) =>
        val toolchain = Try(ScalaNativeToolchain.resolveToolchain(platform, logger)).toOption
        Platform.Native(platform.config, toolchain, platform.mainClass)

      case None => defaultPlatform(logger, compileClasspath, compileResources)
    }

    val sbt = project.sbt
    val resolution = project.resolution

    val out = AbsolutePath(project.out)
    val analysisOut = scala
      .flatMap(_.analysis.map(AbsolutePath.apply))
      .getOrElse(out.resolve(Config.Project.analysisFileName(project.name)))

    val sourceRoots = project.sourceRoots.map(_.map(AbsolutePath.apply))

    val tags = project.tags.getOrElse(Nil)
    val projectDirectory = AbsolutePath(project.directory)

    Project(
      project.name,
      projectDirectory,
      project.workspaceDir.map(AbsolutePath.apply),
      project.dependencies,
      instance,
      compileClasspath,
      compileResources,
      setup,
      AbsolutePath(project.classesDir),
      scala.map(_.options).getOrElse(Nil),
      project.java.map(_.options).getOrElse(Nil),
      project.sources.map(AbsolutePath.apply),
      SourcesGlobs.fromConfig(project, logger),
      sourceRoots,
      project.sourceGenerators.getOrElse(Nil).map(SourceGenerator.fromConfig(projectDirectory, _)),
      project.test.map(_.frameworks).getOrElse(Nil),
      project.test.map(_.options).getOrElse(Config.TestOptions.empty),
      AbsolutePath(project.out),
      analysisOut,
      platform,
      sbt,
      resolution,
      tags,
      origin
    )
  }

  def defaultPlatform(
      logger: Logger,
      classpath: List[AbsolutePath],
      resources: List[AbsolutePath],
      jdkConfig: Option[JdkConfig] = None
  ): Platform = {
    val platform = Config.Platform.Jvm(
      Config.JvmConfig.empty,
      None,
      None,
      Some(classpath.map(_.underlying)),
      Some(resources.map(_.underlying))
    )
    val env = jdkConfig.getOrElse(JdkConfig.fromConfig(platform.config))
    val toolchain = JvmToolchain.resolveToolchain(platform, logger)
    Platform.Jvm(env, toolchain, platform.mainClass, None, classpath, resources)
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
      scalaSemanticDBPlugin: Option[AbsolutePath],
      javaSemanticDBPlugin: Option[AbsolutePath],
      logger: Logger
  ): Project = {
    val workspaceDir = project.workspaceDirectory.getOrElse(configDir.getParent)
    val isDotty = project.scalaInstance.exists(_.isDotty)

    def isAtLeastScala3M3(version: String) = {
      version.startsWith("3.") &&
      version != "3.0.0-M1"
      version != "3.0.0-M2"
    }

    def enableScalaSemanticdb(options: List[String], pluginPath: AbsolutePath): List[String] = {
      val baseSemanticdbOptions = List(
        "-P:semanticdb:failures:warning",
        "-P:semanticdb:synthetics:on",
        "-Xplugin-require:semanticdb"
      )
      // TODO: Handle user-configured `targetroot`s inside Bloop's compilation
      // engine so that semanticdb files are replicated in those directories
      val hasSemanticDB = hasScalaSemanticDBEnabledInCompilerOptions(options)
      val pluginOption = if (hasSemanticDB) Nil else List(s"-Xplugin:$pluginPath")
      val baseOptions = s"-P:semanticdb:sourceroot:$workspaceDir" :: options.filterNot(
        isScalaSemanticdbSourceRoot
      )
      (baseOptions ++ baseSemanticdbOptions ++ pluginOption).distinct
    }

    def enableDottySemanticdb(options: List[String]) = {
      val semanticdbFlag =
        if (project.scalaInstance.exists(instance => isAtLeastScala3M3(instance.version))) {
          "-Xsemanticdb"
        } else {
          "-Ysemanticdb"
        }

      val ysemanticdb = if (!options.contains(semanticdbFlag)) List(semanticdbFlag) else Nil
      val sourceRoot =
        if (!options.contains("-sourceroot")) List("-sourceroot", workspaceDir.toString()) else Nil
      options ++ ysemanticdb ++ sourceRoot
    }

    def enableJavaSemanticdbOptions(options: List[String]): List[String] = {
      if (hasJavaSemanticDBEnabledInCompilerOptions(options)) options
      else {
        val semanticdbOptions =
          s"-Xplugin:semanticdb -sourceroot:${workspaceDir} -targetroot:javac-classes-directory" :: options
        if (project.javaVersionAtLeast("17", logger))
          List(
            "-J--add-exports",
            "-Jjdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "-J--add-exports",
            "-Jjdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "-J--add-exports",
            "-Jjdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
            "-J--add-exports",
            "-Jjdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "-J--add-exports",
            "-Jjdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
          ) ::: semanticdbOptions
        else semanticdbOptions
      }
    }

    def enableJavaSemanticdbClasspath(
        pluginPath: AbsolutePath,
        classpath: List[AbsolutePath]
    ): List[AbsolutePath] = {
      if (classpath.contains(pluginPath)) classpath else pluginPath :: classpath
    }

    def enableRangePositions(options: List[String]): List[String] = {
      val hasYrangepos = options.exists(_.contains("-Yrangepos"))
      if (hasYrangepos || isDotty) options else options :+ "-Yrangepos"
    }

    val projectWithRangePositions =
      if (project.scalaInstance.nonEmpty)
        project.copy(scalacOptions = enableRangePositions(project.scalacOptions))
      else
        project

    val rangedScalacOptions = projectWithRangePositions.scalacOptions
    val javacOptions = projectWithRangePositions.javacOptions

    val scalaProjectWithRangePositions = scalaSemanticDBPlugin match {
      case _ if isDotty =>
        val scalacOptionsWithSemanticDB = enableDottySemanticdb(rangedScalacOptions)
        projectWithRangePositions.copy(scalacOptions = scalacOptionsWithSemanticDB)
      case None =>
        projectWithRangePositions
      case Some(pluginPath) =>
        val scalacOptionsWithSemanticDB = enableScalaSemanticdb(rangedScalacOptions, pluginPath)
        projectWithRangePositions.copy(scalacOptions = scalacOptionsWithSemanticDB)
    }
    javaSemanticDBPlugin match {
      case None =>
        scalaProjectWithRangePositions
      case Some(pluginPath) =>
        val javacOptionsWithSemanticDB = enableJavaSemanticdbOptions(javacOptions)
        val classpathWithSemanticDB =
          enableJavaSemanticdbClasspath(pluginPath, scalaProjectWithRangePositions.rawClasspath)
        scalaProjectWithRangePositions.copy(
          javacOptions = javacOptionsWithSemanticDB,
          rawClasspath = classpathWithSemanticDB
        )
    }
  }

  def hasScalaSemanticDBEnabledInCompilerOptions(options: List[String]): Boolean = {
    options.exists(opt => opt.contains("-Xplugin") && opt.contains("semanticdb-scalac"))
  }

  def hasJavaSemanticDBEnabledInCompilerOptions(options: List[String]): Boolean = {
    options.exists(f => f.contains("-Xplugin") && f.contains("semanticdb"))
  }

  def isScalaSemanticdbSourceRoot(option: String): Boolean = {
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

  def pickValidResources(resources: List[AbsolutePath]): Array[AbsolutePath] = {
    // Only add those resources that exist at the moment of creating the classpath
    resources.iterator.filter(_.exists).toArray
  }

}
