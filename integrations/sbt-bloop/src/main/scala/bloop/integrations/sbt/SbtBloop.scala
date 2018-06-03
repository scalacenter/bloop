package bloop.integrations.sbt

import java.nio.file.Path

import bloop.config.Config
import sbt.{
  AutoPlugin,
  ClasspathDep,
  ClasspathDependency,
  Compile,
  Configuration,
  Def,
  File,
  Global,
  Keys,
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
  import sbt.{SettingKey, TaskKey, settingKey, taskKey}

  val bloopConfigDir: SettingKey[File] =
    settingKey[File]("Directory where to write bloop configuration files")
  val bloopAggregateSourceDependencies: SettingKey[Boolean] =
    settingKey[Boolean]("Flag to tell bloop to aggregate bloop config files in the same bloop dir.")
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
  val bloopDependencies: SettingKey[Seq[ClasspathDependency]] =
    settingKey[Seq[ClasspathDependency]]("Additional dependencies between Bloop projects")
  val bloopInternalClasspath: TaskKey[Seq[(File, File)]] =
    taskKey[Seq[(File, File)]]("Directory where to write the class files")
  val bloopInstall: TaskKey[Unit] =
    taskKey[Unit]("Generate all bloop configuration files")
  val bloopGenerate: sbt.TaskKey[File] =
    sbt.taskKey[File]("Generate bloop configuration files for this project")
  val bloopAnalysisOut: SettingKey[Option[File]] =
    settingKey[Option[File]]("User-defined location for the incremental analysis file.")
}

object BloopDefaults {
  import Compat._
  import sbt.{Task, Defaults, State}

  lazy val globalSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopInstall := bloopInstall.value,
    BloopKeys.bloopAggregateSourceDependencies := false
  )

  // We create build setting proxies to global settings so that we get autocompletion (sbt bug)
  lazy val buildSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopInstall := BloopKeys.bloopInstall.in(Global).value,
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
      BloopKeys.bloopAnalysisOut := None,
      BloopKeys.bloopDependencies := Seq.empty
    ) ++ DiscoveredSbtPlugins.settings

  lazy val testConfigSettings: Seq[Def.Setting[_]] =
    configSettings :+ (BloopKeys.bloopDependencies := bloopMainDependency.value)

  lazy val projectSettings: Seq[Def.Setting[_]] =
    sbt.inConfig(Compile)(configSettings) ++
      sbt.inConfig(Test)(testConfigSettings) ++
      List(
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

  object Feedback {
    def unknownConfigurations(p: ResolvedProject,
                              confs: Seq[String],
                              from: ProjectRef): String = {
      s"""Project ${p.id} depends on unsupported configuration(s) ${confs.mkString(", ")} of ${from.project}.
         |Bloop will assume this dependency goes to the test configuration.
         |Report upstream if you run into trouble: https://github.com/scalacenter/bloop/issues/new
       """.stripMargin
    }
  }

  lazy val bloopGenerate: Def.Initialize[Task[File]] = Def.task {
    val logger = Keys.streams.value.log
    val project = Keys.thisProject.value
      def nameFromString(name: String, configuration: Configuration): String =
        if (configuration == Compile) name else s"$name-${configuration.name}"

    def nameFromRef(dep: ClasspathDep[ProjectRef], configuration: Configuration): String = {
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
            case Nil => nameFromString(ref.project, configuration)
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
        case None => nameFromString(ref.project, configuration)
      }

    }

    def nameFromDependency(dependency: ClasspathDependency): String = {
      import sbt.{LocalProject, LocalRootProject, RootProject}

      val projectName = dependency.project match {
        case ThisProject => Keys.thisProject.value.id
        case LocalProject(project) => project
        // Not sure about these three:
        case LocalRootProject => "root"
        case ProjectRef(build, project) => project
        case RootProject(build) => "root"
      }

      val name = dependency.configuration match {
        case Some("compile") | None => projectName
        case Some(configurationName) => s"$projectName-$configurationName"
      }

      name
    }

    val configuration = Keys.configuration.value
    val projectName = nameFromString(project.id, configuration)
    val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
    val buildBaseDirectory = Keys.baseDirectory.in(ThisBuild).value.getAbsoluteFile
    val rootBaseDirectory = new File(Keys.loadedBuild.value.root)

    /* This is mostly for dependencies which are implicit in sbt, like test->compile.
     * Ideally, we would use something like `configuration.extendsConfigs` to get these
     * dependencies, but it seems that sbt prefers to keep them secret.
     * By default we add the test->compile dependency, but users can override this. */
    val baseProjectDependencies = BloopKeys.bloopDependencies.value.map(nameFromDependency(_))

    val projectDependencies =
      project.dependencies.map(dep => nameFromRef(dep, configuration)).toList
    val dependencies = (projectDependencies ++ baseProjectDependencies).toArray
    val aggregates = project.aggregate.map(agg => nameFromString(agg.project, configuration))
    val dependenciesAndAggregates = dependencies ++ aggregates

    val bloopConfigDir = BloopKeys.bloopConfigDir.value
    val out = (bloopConfigDir / project.id).toPath.toAbsolutePath
    val scalaName = "scala-compiler"
    val scalaVersion = Keys.scalaVersion.value
    val scalaOrg = Keys.ivyScala.value.map(_.scalaOrganization).getOrElse("org.scala-lang")
    val allScalaJars = Keys.scalaInstance.value.allJars.map(_.toPath.toAbsolutePath).toArray

    val classpath =
      emulateDependencyClasspath.value.map(_.toPath.toAbsolutePath).toArray
    val classpathOptions = {
      val cpo = Keys.classpathOptions.value
      Config.ClasspathOptions(cpo.bootLibrary,
                              cpo.compiler,
                              cpo.extra,
                              cpo.autoBoot,
                              cpo.filterLibrary)
    }

    val classesDir = BloopKeys.bloopProductDirectories.value.head.toPath()

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
        Keys.testFrameworks.value.map(f => Config.TestFramework(f.implClassNames.toList)).toArray
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

    // TODO(jvican): Override classes directories here too (e.g. plugins are defined in the build)
    val scalacOptions = {
      val scalacOptions0 = Keys.scalacOptions.value.toArray
      val internalClasspath = BloopKeys.bloopInternalClasspath.value
      internalClasspath.foldLeft(scalacOptions0) {
        case (scalacOptions, (oldClassesDir, newClassesDir)) =>
          val old1 = oldClassesDir.toString
          val old2 = oldClassesDir.getAbsolutePath
          val newClassesDirAbs = newClassesDir.getAbsolutePath
          scalacOptions.map { scalacOption =>
            if (scalacOptions.contains(old1) ||
                scalacOptions.contains(old2)) {
              logger.warn(
                s"The scalac option '$scalacOption' contains a reference to '$oldClassesDir'. Bloop will replace it by its own path optimistically, if you find misbehaviours please open a ticket at https://github.com/scalacenter/bloop.")
              scalacOption.replace(old1, newClassesDirAbs).replace(old2, newClassesDirAbs)
            } else scalacOption
          }
      }
    }

    val compileOrder = Keys.compileOrder.value match {
      case CompileOrder.JavaThenScala => Config.JavaThenScala
      case CompileOrder.ScalaThenJava => Config.ScalaThenJava
      case CompileOrder.Mixed => Config.Mixed
    }

    val javacOptions = Keys.javacOptions.value.toArray
    val (javaHome, javaOptions) = javaConfiguration.value
    val outFile = bloopConfigDir / s"$projectName.json"

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
      val project = Config.Project(projectName, baseDirectory, sources, dependenciesAndAggregates, classpath, classpathOptions, compileOptions, out, analysisOut, classesDir, `scala`, jvm, java, testOptions)
      Config.File(Config.File.LatestVersion, project)
    }
    // format: ON

    sbt.IO.createDirectory(bloopConfigDir)
    Config.File.write(config, outFile.toPath())
    logger.debug(s"Bloop wrote the configuration of project '$projectName' to '$outFile'.")

    val allInRoot = BloopKeys.bloopAggregateSourceDependencies.in(Global).value
    // Only shorten path for configuration files written to the the root build
    val relativeConfigPath = {
      if (allInRoot || buildBaseDirectory == rootBaseDirectory)
        outFile.relativeTo(rootBaseDirectory).getOrElse(outFile)
      else outFile
    }

    logger.success(s"Generated $relativeConfigPath")
    outFile
  }

  private final val allJson = sbt.GlobFilter("*.json")
  private final val removeStaleProjects = { (allConfigDirs: Set[File]) =>
    { (s: State, generatedFiles: Set[File]) =>
      val logger = s.globalLogging.full
      val allConfigs =
        allConfigDirs.flatMap(configDir => sbt.PathFinder(configDir).*(allJson).get)
      allConfigs.diff(generatedFiles).foreach { configFile =>
        sbt.IO.delete(configFile)
        logger.warn(s"Removed stale $configFile.")
      }
      s
    }
  }

  lazy val bloopInstall: Def.Initialize[Task[Unit]] = Def.taskDyn {
    val filter = sbt.ScopeFilter(sbt.inAnyProject, sbt.inAnyConfiguration, sbt.inTasks(BloopKeys.bloopGenerate))
    val allConfigDirs =
      BloopKeys.bloopConfigDir.?.all(sbt.ScopeFilter(sbt.inAnyProject))
        .map(_.flatMap(_.toList).toSet)
        .value
    val removeProjects = removeStaleProjects(allConfigDirs)
    BloopKeys.bloopGenerate
      .all(filter)
      .map(_.toSet)
      // Smart trick to modify state once a task has completed (who said tasks cannot alter state?)
      .apply((t: Task[Set[File]]) => sbt.SessionVar.transform(t, removeProjects))
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
