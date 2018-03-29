package bloop.integrations.sbt

import bloop.integrations.{BloopConfig, ClasspathOptions}
import sbt.{
  AutoPlugin,
  Compile,
  Configuration,
  Def,
  File,
  Global,
  Keys,
  ProjectRef,
  ResolvedProject,
  Test,
  ThisBuild
}

object SbtBloop extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  override def requires = JvmPlugin
  override def trigger = allRequirements
  final val autoImport = AutoImported

  override def globalSettings: Seq[Def.Setting[_]] = PluginImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] = PluginImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] = PluginImplementation.projectSettings
}

object AutoImported {
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
  val bloopInternalClasspath: TaskKey[Seq[(File, File)]] =
    taskKey[Seq[(File, File)]]("Directory where to write the class files")
  val bloopInstall: TaskKey[Unit] =
    taskKey[Unit]("Generate all bloop configuration files")
  val bloopGenerate: sbt.TaskKey[Unit] =
    sbt.taskKey[Unit]("Generate bloop configuration files for this project")
}

object PluginImplementation {
  import bloop.integrations.sbt.{AutoImported => BloopKeys}
  val globalSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopInstall := PluginDefaults.bloopInstall.value,
    BloopKeys.bloopAggregateSourceDependencies := false
  )

  // We create build setting proxies to global settings so that we get autocompletion (sbt bug)
  val buildSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopInstall := BloopKeys.bloopInstall.in(Global).value,
    BloopKeys.bloopAggregateSourceDependencies :=
      BloopKeys.bloopAggregateSourceDependencies.in(Global).value
  )

  import Compat._
  val configSettings: Seq[Def.Setting[_]] = {
    val rawSettingsInConfigs = List(
      BloopKeys.bloopProductDirectories := List(BloopKeys.bloopClassDirectory.value),
      BloopKeys.bloopManagedResourceDirectories := PluginDefaults.managedResourceDirs.value,
      BloopKeys.bloopClassDirectory := PluginDefaults.generateBloopProductDirectories.value,
      BloopKeys.bloopInternalClasspath := PluginDefaults.bloopInternalDependencyClasspath.value,
      BloopKeys.bloopResourceManaged := BloopKeys.bloopTargetDir.value / "resource_managed",
      BloopKeys.bloopGenerate := PluginDefaults.bloopGenerate.value
    )
    val all = rawSettingsInConfigs ++ DiscoveredSbtPlugins.settings
    all.flatMap(ss => sbt.inConfig(Compile)(ss) ++ sbt.inConfig(Test)(ss))
  }

  val projectSettings: Seq[Def.Setting[_]] = configSettings ++ List(
    BloopKeys.bloopTargetDir := PluginDefaults.bloopTargetDir.value,
    BloopKeys.bloopConfigDir := Def.settingDyn {
      val ref = Keys.thisProjectRef.value
      val rootBuild = sbt.BuildRef(Keys.loadedBuild.value.root)
      Def.setting {
        (BloopKeys.bloopConfigDir in Global).?.value.getOrElse {
          if (BloopKeys.bloopAggregateSourceDependencies.in(Global).value) {
            (Keys.baseDirectory in rootBuild).value / ".bloop-config"
          } else {
            // We do this so that it works nicely with source dependencies.
            (Keys.baseDirectory in ref in ThisBuild).value / ".bloop-config"
          }
        }
      }
    }.value
  )

  object PluginDefaults {
    import Compat._
    import sbt.{Task, Defaults}

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
      val bloopTarget = AutoImported.bloopTargetDir.value
      val classesDir = bloopTarget / (Defaults.prefix(configuration.name) + "classes")
      if (!classesDir.exists()) sbt.IO.createDirectory(classesDir)
      classesDir
    }

    object Feedback {
      def unknownConfiguration(p: ResolvedProject, conf: String, from: ProjectRef): String = {
        s"""Project ${p.id} depends on unsupported configuration $conf of ${from.project}.
           |Bloop will assume this dependency goes to the test configuration.
           |Report upstream if you run into trouble: https://github.com/scalacenter/bloop/issues/new
         """.stripMargin
      }
    }

    lazy val bloopGenerate: Def.Initialize[Task[Unit]] = Def.task {
      val logger = Keys.streams.value.log
      val project = Keys.thisProject.value
      def nameFromString(name: String, configuration: Configuration): String =
        if (configuration == Compile) name else name + "-test"

      def nameFromRef(ref: ProjectRef, configuration: Configuration): String = {
        ref.configuration match {
          case Some(conf) if Compile.name == conf => ref.project
          case Some(conf) if Test.name == conf => s"${ref.project}-test"
          case Some(unknown) =>
            logger.warn(Feedback.unknownConfiguration(project, unknown, ref))
            s"${ref.project}-test"
          case None => nameFromString(ref.project, configuration)
        }
      }

      val configuration = Keys.configuration.value
      val projectName = nameFromString(project.id, configuration)
      val baseDirectory = Keys.baseDirectory.value.getAbsoluteFile
      val buildBaseDirectory = Keys.baseDirectory.in(ThisBuild).value.getAbsoluteFile

      // In the test configuration, add a dependency on the base project
      val baseProjectDependency = if (configuration == Test) List(project.id) else Nil

      // TODO: We should extract the right configuration for the dependency.
      val projectDependencies =
        project.dependencies.map(dep => nameFromRef(dep.project, configuration))
      val dependencies = projectDependencies ++ baseProjectDependency
      // TODO: We should extract the right configuration for the aggregate.
      val aggregates = project.aggregate.map(agg => nameFromString(agg.project, configuration))
      val dependenciesAndAggregates = dependencies ++ aggregates

      val bloopConfigDir = BloopKeys.bloopConfigDir.value
      val scalaName = "scala-compiler"
      val scalaVersion = Keys.scalaVersion.value
      val scalaOrg = Keys.ivyScala.value.map(_.scalaOrganization).getOrElse("org.scala-lang")
      val allScalaJars = Keys.scalaInstance.value.allJars.map(_.getAbsoluteFile)
      val classpath = PluginDefaults.emulateDependencyClasspath.value.map(_.getAbsoluteFile)
      val classpathOptions = {
        val cpo = Keys.classpathOptions.value
        ClasspathOptions(cpo.bootLibrary, cpo.compiler, cpo.extra, cpo.autoBoot, cpo.filterLibrary)
      }
      val classesDir = AutoImported.bloopProductDirectories.value.head
      val sourceDirs = Keys.sourceDirectories.value
      val testFrameworks = Keys.testFrameworks.value.map(_.implClassNames)
      // TODO(jvican): Override classes directories here too (e.g. plugins are defined in the build)

      val scalacOptions = {
        val scalacOptions0 = Keys.scalacOptions.value
        val internalClasspath = AutoImported.bloopInternalClasspath.value
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

      val javacOptions = Keys.javacOptions.value

      val (javaHome, javaOptions) = javaConfiguration.value

      val tmp = Keys.target.value / "tmp-bloop"
      val outFile = bloopConfigDir / s"$projectName.config"

      // Force source generators on this task manually
      Keys.managedSources.value

      // Copy the resources, so that they're available when running and testing
      bloopCopyResourcesTask.value

      // format: OFF
      val config = BloopConfig(projectName, baseDirectory, dependenciesAndAggregates, scalaOrg,
        scalaName, scalaVersion, classpath, classpathOptions,
        classesDir, scalacOptions, javacOptions, sourceDirs,  testFrameworks, javaHome, javaOptions,
        allScalaJars, tmp)
      sbt.IO.createDirectory(bloopConfigDir)
      config.writeTo(outFile)
      logger.debug(s"Bloop wrote the configuration of project '$projectName' to '$outFile'.")
      val relativeConfigPath = outFile.relativeTo(buildBaseDirectory).getOrElse(outFile)
      logger.success(s"Generated $relativeConfigPath")
      // format: ON
    }

    lazy val bloopInstall: Def.Initialize[Task[Unit]] = Def.taskDyn {
      val filter = sbt.ScopeFilter(sbt.inAnyProject, sbt.inConfigurations(Compile, Test))
      BloopKeys.bloopGenerate.all(filter).map(_ => ())
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
            val bloopKey = (AutoImported.bloopProductDirectories in (dep, sbt.ConfigKey(c)))
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
      val internalClasspath = AutoImported.bloopInternalClasspath.value.map(_._2)
      val externalClasspath = Keys.externalDependencyClasspath.value.map(_.data)
      internalClasspath ++ externalClasspath
    }

    def bloopCopyResourcesTask = Def.taskDyn {
      val configKey = sbt.ConfigKey(Keys.configuration.value.name)
      Def.task {
        import sbt._
        val t = AutoImported.bloopClassDirectory.value
        val dirs =
          Classpaths
            .concatSettings(Keys.unmanagedResourceDirectories.in(configKey),
                            AutoImported.bloopManagedResourceDirectories.in(configKey))
            .value
        val s = Keys.streams.value
        val cacheStore = Compat.generateCacheFile(s, "copy-resources-bloop")
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
          AutoImported.bloopResourceManaged.in(configKey).value / Defaults.nameForSrc(configName)
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
}
