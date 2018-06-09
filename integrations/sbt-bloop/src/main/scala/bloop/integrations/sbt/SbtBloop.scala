package bloop.integrations.sbt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import bloop.config.Config
import bloop.integration.sbt.Feedback
import sbt.{
  AutoPlugin,
  ClasspathDep,
  ClasspathDependency,
  Compile,
  ConfigKey,
  Configuration,
  Def,
  File,
  Global,
  Keys,
  LocalRootProject,
  Logger,
  Project,
  ProjectRef,
  ResolvedProject,
  Test,
  ThisBuild,
  ThisProject
}
import xsbti.compile.CompileOrder

object BloopPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  override def requires = JvmPlugin
  override def trigger = allRequirements
  final val autoImport = BloopKeys

  override def globalSettings: Seq[Def.Setting[_]] = BloopDefaults.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] = BloopDefaults.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] = BloopDefaults.projectSettings
}

object BloopKeys {
  import sbt.{SettingKey, TaskKey, settingKey, taskKey, UpdateReport}

  val bloopConfigDir: SettingKey[File] =
    settingKey[File]("Directory where to write bloop configuration files")
  val bloopIsMetaBuild: SettingKey[Boolean] =
    settingKey[Boolean]("Is this a meta build?")
  val bloopAggregateSourceDependencies: SettingKey[Boolean] =
    settingKey[Boolean]("Flag to tell bloop to aggregate bloop config files in the same bloop dir.")
  val bloopExportSourceAndDocJars: SettingKey[Boolean] =
    settingKey[Boolean]("Export the source and javadoc jars to the bloop configuration file")
  val bloopProductDirectories: TaskKey[Seq[File]] =
    taskKey[Seq[File]]("Bloop product directories")
  val bloopManagedResourceDirectories: SettingKey[Seq[File]] =
    settingKey[Seq[File]]("Managed resource directories for bloop")
  val bloopClassDirectory: SettingKey[File] =
    settingKey[File]("Directory where to write the class files")
  val bloopTargetDir: SettingKey[File] =
    settingKey[File]("Target directory for the pertinent project and configuration")
  val bloopResourceManaged: SettingKey[File] =
    settingKey[File]("Resource managed for bloop")
  val bloopInternalClasspath: TaskKey[Seq[(File, File)]] =
    taskKey[Seq[(File, File)]]("Directory where to write the class files")
  val bloopInstall: TaskKey[Unit] =
    taskKey[Unit]("Generate all bloop configuration files")
  val bloopGenerate: sbt.TaskKey[Option[File]] =
    taskKey[Option[File]]("Generate bloop configuration files for this project")
  val bloopAnalysisOut: SettingKey[Option[File]] =
    settingKey[Option[File]]("User-defined location for the incremental analysis file.")
}

object BloopDefaults {
  import Compat._
  import sbt.{Task, Defaults, State}

  lazy val globalSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopInstall := bloopInstall.value,
    BloopKeys.bloopAggregateSourceDependencies := false,
    BloopKeys.bloopIsMetaBuild := Keys.sbtPlugin.in(LocalRootProject).value,
    Keys.onLoad := {
      { (state: State) =>
        val isMetaBuild = BloopKeys.bloopIsMetaBuild.value
        if (isMetaBuild) {
          val extracted = Project.extract(state)
          runCommandAndRemaining("bloopInstall")(state)
        } else state
      }
    }
  )

  // From the infamous https://stackoverflow.com/questions/40741244/in-sbt-how-to-execute-a-command-in-task
  def runCommandAndRemaining(command: String): State => State = { st: State =>
    import sbt.complete.Parser
    @annotation.tailrec
    def runCommand(command: String, state: State): State = {
      val nextState = Parser.parse(command, state.combinedParser) match {
        case Right(cmd) => cmd()
        case Left(msg) => throw sys.error(s"Invalid programmatic input:\n$msg")
      }
      nextState.remainingCommands.toList match {
        case Nil => nextState
        case head :: tail => runCommand(head, nextState.copy(remainingCommands = tail))
      }
    }
    runCommand(command, st.copy(remainingCommands = Nil))
      .copy(remainingCommands = st.remainingCommands)
  }

  // We create build setting proxies to global settings so that we get autocompletion (sbt bug)
  lazy val buildSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopInstall := BloopKeys.bloopInstall.in(Global).value,
    BloopKeys.bloopExportSourceAndDocJars := false,
    // Bloop users: Do NEVER override this setting as a user if you want it to work
    BloopKeys.bloopAggregateSourceDependencies :=
      BloopKeys.bloopAggregateSourceDependencies.in(Global).value
  )

  lazy val configSettings: Seq[Def.Setting[_]] =
    List(
      BloopKeys.bloopProductDirectories := List(BloopKeys.bloopClassDirectory.value),
      BloopKeys.bloopManagedResourceDirectories := managedResourceDirs.value,
      BloopKeys.bloopClassDirectory := generateBloopProductDirectories.value,
      BloopKeys.bloopInternalClasspath := bloopInternalDependencyClasspath.value,
      BloopKeys.bloopResourceManaged := BloopKeys.bloopTargetDir.value / "resource_managed",
      BloopKeys.bloopGenerate := bloopGenerate.value,
      BloopKeys.bloopAnalysisOut := None
    ) ++ DiscoveredSbtPlugins.settings

  lazy val projectSettings: Seq[Def.Setting[_]] = {
    sbt.inConfig(Compile)(configSettings) ++
      sbt.inConfig(Test)(configSettings) ++
      List(
        // Override checksums so that `updates` don't check md5 for all jars
        Keys.checksums in Keys.update := Vector("sha1"),
        Keys.checksums in Keys.updateClassifiers := Vector("sha1"),
        BloopKeys.bloopTargetDir := bloopTargetDir.value,
        BloopKeys.bloopConfigDir := Def.settingDyn {
          val ref = Keys.thisProjectRef.value
          val rootBuild = sbt.BuildRef(Keys.loadedBuild.value.root)
          Def.setting {
            (BloopKeys.bloopConfigDir in Global).?.value.getOrElse {
              if (BloopKeys.bloopAggregateSourceDependencies.in(Global).value) {
                (Keys.baseDirectory in rootBuild).value / ".bloop"
              } else {
                // We do this so that it works nicely with source dependencies.
                (Keys.baseDirectory in ref in ThisBuild).value / ".bloop"
              }
            }
          }
        }.value
      )
  }

  private final val ScalaNativePluginLabel = "scala.scalanative.sbtplugin.ScalaNativePlugin"
  private final val ScalaJsPluginLabel = "org.scalajs.sbtplugin.ScalaJSPlugin"

  lazy val bloopTargetDir: Def.Initialize[File] = Def.setting {
    val project = Keys.thisProject.value
    val bloopConfigDir = BloopKeys.bloopConfigDir.value
    Defaults.makeCrossTarget(
      bloopConfigDir / project.id,
      Keys.scalaBinaryVersion.value,
      (Keys.sbtBinaryVersion in Keys.pluginCrossBuild).value,
      Keys.sbtPlugin.value,
      Keys.crossPaths.value
    )
  }

  lazy val generateBloopProductDirectories: Def.Initialize[File] = Def.setting {
    val configuration = Keys.configuration.value
    val bloopTarget = BloopKeys.bloopTargetDir.value
    val classesDir = bloopTarget / (Defaults.prefix(configuration.name) + "classes")
    if (!classesDir.exists()) sbt.IO.createDirectory(classesDir)
    classesDir
  }

  lazy val bloopMainDependency: Def.Initialize[Seq[ClasspathDependency]] = Def.setting {
    Seq(ClasspathDependency(ThisProject, Some(Compile.name)))
  }

  // Select only the sources that are not present in the source directories
  def pruneSources(sourceDirs: Seq[Path], sources: Seq[Path]): Seq[Path] = {
    def checkIfParent(parent: Path, potentialParent: Path): Boolean = {
      if (potentialParent == null) false
      else if (potentialParent == parent) true
      else checkIfParent(parent, potentialParent.getParent)
    }

    val realSources = sources.map(_.toAbsolutePath())
    sources.filter(source => !sourceDirs.exists(dir => checkIfParent(dir, source.getParent)))
  }

  /**
   * Detect the eligible configuration dependencies from a given configuration.
   *
   * A configuration is elibile if the project defines it and `bloopGenerate`
   * exists for it. Otherwise, the configuration dependency is ignored.
   *
   * This is required to prevent transitive configurations like `Runtime` from
   * generating useless bloop configuration files and possibly incorrect project
   * dependencies. For example, if we didn't do this then the dependencies of
   * `IntegrationTest` would be `projectName-runtime` and `projectName-compile`,
   * whereas the following logic will return only the configuration `Compile`
   * so that the use site of this function can create the project dep
   * `projectName-compile`.
   */
  lazy val eligibleDepsFromConfig: Def.Initialize[Task[List[Configuration]]] = {
    Def.task {
      def depsFromConfig(configuration: Configuration): List[Configuration] = {
        configuration.extendsConfigs.toList match {
          case config :: Nil if config.extendsConfigs.isEmpty => config :: Nil
          case config :: Nil => config :: depsFromConfig(config)
          case Nil => Nil
        }
      }

      val config = Keys.configuration.value
      val configs = depsFromConfig(config)
      val activeProjectConfigs = Keys.thisProject.value.configurations.toSet

      import scala.collection.JavaConverters._
      val data = Keys.settingsData.value
      val thisProjectRef = Keys.thisProjectRef.value
      val productDirs = (new java.util.LinkedHashSet[Task[File]]).asScala
      val eligibleConfigs = activeProjectConfigs.filter { c =>
        val configKey = ConfigKey.configurationToKey(c)
        val eligibleKey = (BloopKeys.bloopGenerate in (thisProjectRef, configKey))
        eligibleKey.get(data) match {
          case Some(t) =>
            // Sbt seems to return tasks for the extended configurations (looks like a big bug)
            t.info.get(Keys.taskDefinitionKey) match {
              // So we now make sure that the returned config key matches the original one
              case Some(taskDef) => taskDef.scope.config.toOption.toList.contains(configKey)
              case None => true
            }
          case None => false
        }
      }

      configs.filter(c => eligibleConfigs.contains(c))
    }
  }

  def projectNameFromString(name: String, configuration: Configuration): String =
    if (configuration == Compile) name else s"$name-${configuration.name}"

  /**
   * Creates a project name from a classpath dependency and its configuration.
   *
   * This function uses internal sbt utils (`sbt.Classpaths`) to parse configuration
   * dependencies like sbt does and extract them. This parsing only supports compile
   * and test, any kind of other dependency will be assumed to be test and will be
   * reported to the user.
   *
   * Ref https://www.scala-sbt.org/1.x/docs/Library-Management.html#Configurations.
   */
  def projectDependencyName(
      dep: ClasspathDep[ProjectRef],
      configuration: Configuration,
      project: ResolvedProject,
      logger: Logger
  ): String = {
    val ref = dep.project
    dep.configuration match {
      case Some(_) =>
        val mapping = sbt.Classpaths.mapped(
          dep.configuration,
          List("compile", "test"),
          List("compile", "test"),
          "compile",
          "*->compile"
        )

        mapping(configuration.name) match {
          case Nil => projectNameFromString(ref.project, configuration)
          case List(conf) if Compile.name == conf => ref.project
          case List(conf) if Test.name == conf => s"${ref.project}-test"
          case List(conf1, conf2) if Test.name == conf1 && Compile.name == conf2 =>
            s"${ref.project}-test"
          case List(conf1, conf2) if Compile.name == conf1 && Test.name == conf2 =>
            s"${ref.project}-test"
          case unknown =>
            logger.warn(Feedback.unknownConfigurations(project, unknown, ref))
            s"${ref.project}-test"
        }
      case None => projectNameFromString(ref.project, configuration)
    }
  }

  // Unused for now, but we leave it here because it may be useful in the future.
  def nameFromDependency(dependency: ClasspathDependency, thisProject: ResolvedProject): String = {
    import sbt.{LocalProject, LocalRootProject, RootProject}

    val projectName = dependency.project match {
      case ThisProject => thisProject.id
      case LocalProject(project) => project
      // Not sure about these three:
      case LocalRootProject => "root"
      case ProjectRef(build, project) => project
      case RootProject(build) => "root"
    }

    dependency.configuration match {
      case Some("compile") | None => projectName
      case Some(configurationName) => s"$projectName-$configurationName"
    }
  }

  /**
   * Replace any old path that is used as a scalac option by the new path.
   *
   * This will make the code correct in the case sbt has references to
   * the classes directory in the scalac option parameter.
   */
  def replaceScalacOptionsPaths(
      opts: Array[String],
      internalClasspath: Seq[(File, File)],
      logger: Logger
  ): Array[String] = {
    internalClasspath.foldLeft(opts) {
      case (scalacOptions, (oldClassesDir, newClassesDir)) =>
        val old1 = oldClassesDir.toString
        val old2 = oldClassesDir.getAbsolutePath
        val newClassesDirAbs = newClassesDir.getAbsolutePath
        scalacOptions.map { scalacOption =>
          if (scalacOptions.contains(old1) ||
              scalacOptions.contains(old2)) {
            logger.warn(Feedback.warnReferenceToClassesDir(scalacOption, oldClassesDir.toString))
            scalacOption.replace(old1, newClassesDirAbs).replace(old2, newClassesDirAbs)
          } else scalacOption
        }
    }
  }

  def checksumFor(path: Path, algorithm: String): Option[Config.Checksum] = {
    val presumedChecksumFilename = s"${path.getFileName}.$algorithm"
    val presumedChecksum = path.getParent.resolve(presumedChecksumFilename)
    if (!Files.exists(presumedChecksum) || Files.isDirectory(presumedChecksum)) None
    else {
      import scala.util.{Try, Success, Failure}
      Try(new String(Files.readAllBytes(presumedChecksum), StandardCharsets.UTF_8)) match {
        case Success(checksum) => Some(Config.Checksum(algorithm, checksum))
        case Failure(_) => None
      }
    }
  }

  def configModules(report: sbt.UpdateReport): Seq[Config.Module] = {
    val moduleReports = for {
      configuration <- report.configurations
      module <- configuration.modules
    } yield module

    moduleReports.map { mreport =>
      val artifacts = mreport.artifacts.toList.map {
        case (a, f) =>
          val path = f.toPath
          val artifact = toBloopArtifact(a, f)
          artifact.checksum match {
            case Some(_) => artifact
            case None =>
              // If sbt hasn't filled in the checksums field, let's try to do it ourselves
              val checksum = checksumFor(path, "sha1").orElse(checksumFor(path, "md5"))
              artifact.copy(checksum = checksum)
          }
      }

      val m = mreport.module
      Config.Module(m.organization, m.name, m.revision, m.configurations, artifacts)
    }
  }

  def mergeModules(ms0: Seq[Config.Module], ms1: Seq[Config.Module]): Seq[Config.Module] = {
    ms0.map { m0 =>
      ms1.find(m =>
        m0.organization == m.organization && m0.name == m.name && m0.version == m.version) match {
        case Some(m1) => m0.copy(artifacts = m0.artifacts ++ m1.artifacts)
        case None => m0
      }
    }.distinct
  }

  def onlyCompilationModules(ms: Seq[Config.Module], classpath: Array[Path]): Seq[Config.Module] = {
    val classpathFiles = classpath.filter(p => Files.exists(p) && !Files.isDirectory(p))
    if (classpathFiles.isEmpty) Nil
    else {
      ms.filter { m =>
        // The artifacts that have no classifier are the normal binary jars we're interested in
        m.artifacts.filter(a => a.classifier.isEmpty).exists { a =>
          classpathFiles.exists(p => Files.isSameFile(a.path, p))
        }
      }
    }
  }

  lazy val updateClassifiers: Def.Initialize[Task[Option[sbt.UpdateReport]]] = Def.taskDyn {
    val runUpdateClassifiers = BloopKeys.bloopExportSourceAndDocJars.value
    if (!runUpdateClassifiers) Def.task(None)
    else Def.task(Some(Keys.updateClassifiers.value))
  }

  lazy val bloopGenerate: Def.Initialize[Task[Option[File]]] = Def.taskDyn {
    val logger = Keys.streams.value.log
    val project = Keys.thisProject.value
    val configuration = Keys.configuration.value
    val isMetaBuild = BloopKeys.bloopIsMetaBuild.value

    if (isMetaBuild && configuration == Test) Def.task(None)
    else {
      Def.task {
        val projectName = projectNameFromString(project.id, configuration)
        val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
        val buildBaseDirectory = Keys.baseDirectory.in(ThisBuild).value.getAbsoluteFile
        val rootBaseDirectory = new File(Keys.loadedBuild.value.root)

        val dependencies = {
          // Project dependencies come from classpath deps and also inter-project config deps
          val classpathProjectDependencies =
            project.dependencies.map(d => projectDependencyName(d, configuration, project, logger))
          val configDependencies =
            eligibleDepsFromConfig.value.map(c => projectNameFromString(project.id, c))
          // The distinct here is important to make sure that there are no repeated project deps
          (classpathProjectDependencies ++ configDependencies).distinct.toArray
        }

        // Aggregates are considered to be dependencies too for the sake of user-friendliness
        val aggregates =
          project.aggregate.map(agg => projectNameFromString(agg.project, configuration))
        val dependenciesAndAggregates = dependencies ++ aggregates

        val bloopConfigDir = BloopKeys.bloopConfigDir.value
        val out = (bloopConfigDir / project.id).toPath.toAbsolutePath
        val scalaName = "scala-compiler"
        val scalaVersion = Keys.scalaVersion.value
        val scalaOrg = Keys.ivyScala.value.map(_.scalaOrganization).getOrElse("org.scala-lang")
        val allScalaJars = Keys.scalaInstance.value.allJars.map(_.toPath.toAbsolutePath).toArray

        val classesDir = BloopKeys.bloopProductDirectories.value.head.toPath()
        val classpath = emulateDependencyClasspath.value.map(_.toPath.toAbsolutePath).toArray
        val classpathOptions = {
          val c = Keys.classpathOptions.value
          Config.ClasspathOptions(c.bootLibrary, c.compiler, c.extra, c.autoBoot, c.filterLibrary)
        }

        /* This is a best-effort to export source directories + stray source files that
         * are not contained in them. Source directories are superior over source files because
         * they allow us to watch them and detect the creation of new source files in situ. */
        val sources = {
          val sourceDirs = Keys.sourceDirectories.value.map(_.toPath)
          val sourceFiles = pruneSources(sourceDirs, Keys.sources.value.map(_.toPath))
          (sourceDirs ++ sourceFiles).toArray
        }

        val testOptions = {
          val frameworks =
            Keys.testFrameworks.value
              .map(f => Config.TestFramework(f.implClassNames.toList))
              .toArray
          val empty = (List.empty[String], List.empty[Config.TestArgument])
          val options = Keys.testOptions.value.foldLeft(Config.TestOptions.empty) {
            case (options, sbt.Tests.Argument(framework0, args0)) =>
              val args = args0.toArray
              val framework = framework0.map(f => Config.TestFramework(f.implClassNames.toList))
              options.copy(arguments = Config.TestArgument(args, framework) :: options.arguments)
            case (options, sbt.Tests.Exclude(tests)) =>
              options.copy(excludes = tests.toList ++ options.excludes)
            case (options, other: sbt.TestOption) =>
              logger.info(s"Skipped test option '${other}' as it can only be used within sbt.")
              options
          }
          Config.Test(frameworks, options)
        }

        val javacOptions = Keys.javacOptions.value.toArray
        val (javaHome, javaOptions) = javaConfiguration.value
        val scalacOptions = {
          val options = Keys.scalacOptions.value.toArray
          val internalClasspath = BloopKeys.bloopInternalClasspath.value
          replaceScalacOptionsPaths(options, internalClasspath, logger)
        }

        val compileOrder = Keys.compileOrder.value match {
          case CompileOrder.JavaThenScala => Config.JavaThenScala
          case CompileOrder.ScalaThenJava => Config.ScalaThenJava
          case CompileOrder.Mixed => Config.Mixed
        }

        val jsConfig = None
        val nativeConfig = None
        val platform = {
          val pluginLabels = project.autoPlugins.map(_.label).toSet
          if (pluginLabels.contains(ScalaNativePluginLabel)) Config.Platform.Native
          else if (pluginLabels.contains(ScalaJsPluginLabel)) Config.Platform.JS
          else Config.Platform.JVM
        }

        val binaryModules = configModules(Keys.update.value)
        val sourceModules = updateClassifiers.value.toList.flatMap(configModules)
        val allModules = mergeModules(binaryModules, sourceModules)
        val resolution = Config.Resolution(onlyCompilationModules(allModules, classpath).toList)

        // Force source generators on this task manually
        Keys.managedSources.value
        // Copy the resources, so that they're available when running and testing
        bloopCopyResourcesTask.value

        // format: OFF
        val config = {
          val java = Config.Java(javacOptions)
          val `scala` = Config.Scala(scalaOrg, scalaName, scalaVersion, scalacOptions, allScalaJars)
          val jvm = Config.Jvm(Some(javaHome.toPath), javaOptions.toArray)

          val compileOptions = Config.CompileOptions(compileOrder)
          val analysisOut = out.resolve(Config.Project.analysisFileName(projectName))
          val project = Config.Project(projectName, baseDirectory, sources, dependenciesAndAggregates,
            classpath, classpathOptions, compileOptions, out, analysisOut, classesDir, `scala`, jvm,
            java, testOptions, platform, nativeConfig, jsConfig, resolution)
          Config.File(Config.File.LatestVersion, project)
        }
        // format: ON

        sbt.IO.createDirectory(bloopConfigDir)
        val outFile = bloopConfigDir / s"$projectName.json"
        bloop.config.write(config, outFile.toPath)

        // Only shorten path for configuration files written to the the root build
        val allInRoot = BloopKeys.bloopAggregateSourceDependencies.in(Global).value
        val userFriendlyConfigPath = {
          if (allInRoot || buildBaseDirectory == rootBaseDirectory)
            outFile.relativeTo(rootBaseDirectory).getOrElse(outFile)
          else outFile
        }

        logger.debug(s"Bloop wrote the configuration of project '$projectName' to '$outFile'.")
        logger.success(s"Generated $userFriendlyConfigPath")
        Some(outFile)
      }
    }
  }

  private final val allJson = sbt.GlobFilter("*.json")
  private final val removeStaleProjects = { (allConfigDirs: Set[File]) =>
    { (s: State, generatedFiles: Set[Option[File]]) =>
      val logger = s.globalLogging.full
      val allConfigs =
        allConfigDirs.flatMap(configDir => sbt.PathFinder(configDir).*(allJson).get)
      allConfigs.diff(generatedFiles.flatMap(_.toList)).foreach { configFile =>
        sbt.IO.delete(configFile)
        logger.warn(s"Removed stale $configFile.")
      }
      s
    }
  }

  lazy val bloopInstall: Def.Initialize[Task[Unit]] = Def.taskDyn {
    val filter = sbt.ScopeFilter(
      sbt.inAnyProject,
      sbt.inAnyConfiguration,
      sbt.inTasks(BloopKeys.bloopGenerate)
    )

    val allConfigDirs =
      BloopKeys.bloopConfigDir.?.all(sbt.ScopeFilter(sbt.inAnyProject))
        .map(_.flatMap(_.toList).toSet)
        .value
    val removeProjects = removeStaleProjects(allConfigDirs)
    BloopKeys.bloopGenerate
      .all(filter)
      .map(_.toSet)
      // Smart trick to modify state once a task has completed (who said tasks cannot alter state?)
      .apply((t: Task[Set[Option[File]]]) => sbt.SessionVar.transform(t, removeProjects))
      .map(_ => ())
  }

  lazy val bloopConfigDir: Def.Initialize[Option[File]] = Def.setting { None }
  import sbt.Classpaths

  /**
   * Emulates `dependencyClasspath` without triggering compilation of dependent projects.
   *
   * Why do we do this instead of a simple `productDirectories ++ libraryDependencies`?
   * We want the classpath to have the correct topological order of the project dependencies.
   */
  final lazy val bloopInternalDependencyClasspath: Def.Initialize[Task[Seq[(File, File)]]] = {
    Def.taskDyn {
      val currentProject = Keys.thisProjectRef.value
      val data = Keys.settingsData.value
      val deps = Keys.buildDependencies.value
      val conf = Keys.classpathConfiguration.value
      val self = Keys.configuration.value

      import scala.collection.JavaConverters._
      val visited = Classpaths.interSort(currentProject, conf, data, deps)
      val productDirs = (new java.util.LinkedHashSet[Task[Seq[File]]]).asScala
      val bloopProductDirs = (new java.util.LinkedHashSet[Task[Seq[File]]]).asScala
      for ((dep, c) <- visited) {
        if ((dep != currentProject) || (conf.name != c && self.name != c)) {
          val classpathKey = (Keys.productDirectories in (dep, sbt.ConfigKey(c)))
          productDirs += classpathKey.get(data).getOrElse(sbt.std.TaskExtra.constant(Nil))
          val bloopKey = (BloopKeys.bloopProductDirectories in (dep, sbt.ConfigKey(c)))
          bloopProductDirs += bloopKey.get(data).getOrElse(sbt.std.TaskExtra.constant(Nil))
        }
      }

      val generatedTask = (productDirs.toList.join).map(_.flatten.distinct).flatMap { a =>
        (bloopProductDirs.toList.join).map(_.flatten.distinct).map { b =>
          a.zip(b)
        }
      }

      Def.task(generatedTask.value)
    }
  }

  final lazy val emulateDependencyClasspath: Def.Initialize[Task[Seq[File]]] = Def.task {
    val internalClasspath = BloopKeys.bloopInternalClasspath.value.map(_._2)
    val externalClasspath = Keys.externalDependencyClasspath.value.map(_.data)
    internalClasspath ++ externalClasspath
  }

  def bloopCopyResourcesTask = Def.taskDyn {
    val configKey = sbt.ConfigKey(Keys.configuration.value.name)
    Def.task {
      import sbt._
      val t = BloopKeys.bloopClassDirectory.value
      val dirs =
        Classpaths
          .concatSettings(Keys.unmanagedResourceDirectories.in(configKey),
                          BloopKeys.bloopManagedResourceDirectories.in(configKey))
          .value
      val s = Keys.streams.value
      val cacheStore = bloop.integrations.sbt.Compat.generateCacheFile(s, "copy-resources-bloop")
      val mappings = (sbt.PathFinder(Keys.resources.value) --- dirs) pair (sbt.Path
        .rebase(dirs, t) | sbt.Path.flat(t))
      s.log.debug("Copy resource mappings: " + mappings.mkString("\n\t", "\n\t", ""))
      sbt.Sync(cacheStore)(mappings)
      mappings
    }
  }

  def managedResourceDirs: Def.Initialize[Seq[File]] = Def.settingDyn {
    val configName = Keys.configuration.value.name
    val configKey = sbt.ConfigKey(configName)
    Def.setting {
      val oldUnmanagedResourceDirs = Keys.managedResourceDirectories.in(configKey).value
      val oldResourceDir = Keys.resourceManaged.in(configKey).value
      val newResourceDir =
        BloopKeys.bloopResourceManaged.in(configKey).value / Defaults.nameForSrc(configName)
      oldUnmanagedResourceDirs.map { dir =>
        if (dir == oldResourceDir) newResourceDir else dir
      }
    }
  }

  private type JavaConfiguration = (File, Seq[String])

  /**
   * Extract the information that we need to configure forking for run or test.
   */
  val javaConfiguration: Def.Initialize[Task[JavaConfiguration]] = Def.taskDyn {
    import sbt.Scoped
    val configuration = Keys.configuration.value
    lazy val defaultJavaHome = new File(sys.props("java.home"))
    def scoped[T, K[T]](key: Scoped.ScopingSetting[K[T]]): K[T] =
      if (configuration == Test) key.in(Test)
      else key.in(Keys.run)

    Def.task {
      val javaHome = scoped(Keys.javaHome).value.getOrElse(defaultJavaHome)
      val javaOptions = scoped(Keys.javaOptions).value

      (javaHome, javaOptions)
    }
  }
}
