package bloop.integrations.sbt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import bloop.config.Config
import bloop.config.util.ConfigUtil
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
  ProjectRef,
  ResolvedProject,
  Test,
  IntegrationTest,
  ThisBuild,
  ThisProject,
  KeyRanks
}
import xsbti.compile.CompileOrder

import scala.util.{Try, Success, Failure}
import java.util.concurrent.ConcurrentHashMap

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
  import Compat.{CompileAnalysis, CompileResult}
  import sbt.{SettingKey, TaskKey, AttributeKey, ScopedKey, settingKey, taskKey}

  val bloopTargetName: SettingKey[String] =
    settingKey[String]("Bloop target name")
  val bloopConfigDir: SettingKey[File] =
    settingKey[File]("Directory where to write bloop configuration files")
  val bloopIsMetaBuild: SettingKey[Boolean] =
    settingKey[Boolean]("Is this a meta build?")
  val bloopAggregateSourceDependencies: SettingKey[Boolean] =
    settingKey[Boolean]("Flag to tell bloop to aggregate bloop config files in the same bloop dir")
  val bloopExportJarClassifiers: SettingKey[Option[Set[String]]] =
    settingKey[Option[Set[String]]](
      "The classifiers that will be exported with `updateClassifiers`"
    )
  val bloopProductDirectories: TaskKey[Seq[File]] =
    taskKey[Seq[File]]("Bloop product directories")
  val bloopClassDirectory: SettingKey[File] =
    settingKey[File]("Directory where to write the class files")
  val bloopTargetDir: SettingKey[File] =
    settingKey[File]("Target directory for the pertinent project and configuration")
  val bloopInternalClasspath: TaskKey[Seq[(File, File)]] =
    taskKey[Seq[(File, File)]]("Directory where to write the class files")
  val bloopInstall: TaskKey[Unit] =
    taskKey[Unit]("Generate all bloop configuration files")
  val bloopGenerate: TaskKey[Option[File]] =
    taskKey[Option[File]]("Generate bloop configuration file for this project")
  val bloopForceResourceGenerators: TaskKey[Unit] =
    taskKey[Unit]("Force resource generators for Bloop.")

  val bloopCompile: TaskKey[CompileResult] =
    taskKey[CompileResult]("Offload compilation to Bloop via BSP.")
  val bloopAnalysisOut: TaskKey[Option[File]] =
    taskKey[Option[File]]("User-defined location for the incremental analysis file")
  val bloopScalaJSStage: SettingKey[Option[String]] =
    settingKey[Option[String]]("Scala.js-independent definition of `scalaJSStage`")
  val bloopScalaJSModuleKind: SettingKey[Option[String]] =
    settingKey[Option[String]]("Scala.js-independent definition of `scalaJSModuleKind`")
  val bloopMainClass: SettingKey[Option[String]] =
    settingKey[Option[String]]("The main class to run a bloop target")
  val bloopSupportedConfigurations: SettingKey[Seq[Configuration]] =
    settingKey[Seq[Configuration]](
      "The sequence of configurations that are used to detect inter-project dependencies by bloop."
    )

  val bloopGlobalUniqueId: SettingKey[String] =
    sbt.SettingKey[String](
      "bloopGlobalUniqueId",
      "Bloop global unique id to represent a compiled settings universe",
      KeyRanks.Invisible
    )

  val bloopDefinitionKey = AttributeKey[ScopedKey[_]](
    "bloop-definition-key",
    "Internal: used to map a task back to its ScopedKey.",
    KeyRanks.Invisible
  )

  val bloopCompileProxy = AttributeKey[sbt.ScopedKey[_]](
    "bloopCompileProxy",
    "Internal: used to map a task back to its ScopedKey.",
    KeyRanks.Invisible
  )

  val bloopCompileEntrypoint = AttributeKey[sbt.ScopedKey[_]](
    "bloopCompileEntrypoint",
    "Internal: used to map a task back to its ScopedKey.",
    KeyRanks.Invisible
  )

  val bloopWaitForCompile = AttributeKey[sbt.ScopedKey[_]](
    "bloopWaitForCompile",
    "Internal: used to map a task back to its ScopedKey.",
    KeyRanks.Invisible
  )
}

object BloopDefaults {
  import Compat._
  import sbt.{Task, Defaults, State}

  val productDirectoriesUndeprecatedKey =
    sbt.TaskKey[Seq[File]]("productDirectories", rank = KeyRanks.CTask)

  private lazy val cwd: String = System.getProperty("user.dir")
  lazy val globalSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopGlobalUniqueId := bloopGlobalUniqueIdTask.value,
    BloopKeys.bloopExportJarClassifiers := None,
    BloopKeys.bloopInstall := bloopInstall.value,
    BloopKeys.bloopAggregateSourceDependencies := true,
    // Override classifiers so that we don't resolve always docs
    Keys.transitiveClassifiers in Keys.updateClassifiers := {
      val old = (Keys.transitiveClassifiers in Keys.updateClassifiers).value
      val bloopClassifiers = BloopKeys.bloopExportJarClassifiers.in(ThisBuild).value
      (if (bloopClassifiers.isEmpty) old else bloopClassifiers.get).toList
    },
    BloopKeys.bloopIsMetaBuild := {
      val buildStructure = Keys.loadedBuild.value
      val baseDirectory = new File(buildStructure.root)
      val isMetaBuild = Keys.sbtPlugin.in(LocalRootProject).value
      isMetaBuild && baseDirectory.getAbsolutePath != cwd
    },
    Keys.onLoad := {
      val oldOnLoad = Keys.onLoad.value
      oldOnLoad.andThen { state =>
        val isMetaBuild = BloopKeys.bloopIsMetaBuild.value
        if (!isMetaBuild) state
        else runCommandAndRemaining("bloopInstall")(state)
      }
    },
    BloopKeys.bloopSupportedConfigurations := List(Compile, Test, IntegrationTest)
  ) ++ Offloader.bloopCompileGlobalSettings

  // From the infamous https://stackoverflow.com/questions/40741244/in-sbt-how-to-execute-a-command-in-task
  def runCommandAndRemaining(command: String): State => State = { st: State =>
    import sbt.complete.Parser
    @annotation.tailrec
    def runCommand(command: String, state: State): State = {
      val nextState = Parser.parse(command, state.combinedParser) match {
        case Right(cmd) => cmd()
        case Left(msg) => throw sys.error(s"Invalid programmatic input:\n$msg")
      }
      nextState.remainingCommands match {
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
    // Repeat definition so that sbt shows autocopmletion for these settings
    BloopKeys.bloopExportJarClassifiers := BloopKeys.bloopExportJarClassifiers.in(Global).value,
    // Bloop users: Do NEVER override this setting as a user if you want it to work
    BloopKeys.bloopAggregateSourceDependencies :=
      BloopKeys.bloopAggregateSourceDependencies.in(Global).value
  )

  /**
   * These config settings can be applied in configuration that have not yet
   * been enabled in a project. Therefore, their implementations must protect
   * themselves fro depending on tasks that do not exist in the scope (which
   * happens when the configuration is disabled but a task refers to it
   * nonetheless). See an example in the definition of `bloopInternalClasspath`
   * or the implementation of `bloopGenerate`.
   */
  def configSettings: Seq[Def.Setting[_]] =
    List(
      BloopKeys.bloopTargetName := bloopTargetName.value,
      BloopKeys.bloopProductDirectories := List(BloopKeys.bloopClassDirectory.value),
      BloopKeys.bloopClassDirectory := generateBloopProductDirectories.value,
      BloopKeys.bloopInternalClasspath := bloopInternalDependencyClasspath.value,
      BloopKeys.bloopGenerate := bloopGenerate.value,
      BloopKeys.bloopForceResourceGenerators := bloopForceResourceGenerators.value,
      BloopKeys.bloopAnalysisOut := Offloader.bloopAnalysisOut.value,
      BloopKeys.bloopMainClass := None,
      BloopKeys.bloopMainClass in Keys.run := BloopKeys.bloopMainClass.value
    ) ++ discoveredSbtPluginsSettings ++ Offloader.bloopCompileConfigSettings

  lazy val projectSettings: Seq[Def.Setting[_]] = {
    sbt.inConfig(Compile)(configSettings) ++
      sbt.inConfig(Test)(configSettings) ++
      sbt.inConfig(IntegrationTest)(configSettings) ++
      List(
        BloopKeys.bloopScalaJSStage := findOutScalaJsStage.value,
        BloopKeys.bloopScalaJSModuleKind := findOutScalaJsModuleKind.value,
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
      ) ++ Offloader.bloopCompileProjectSettings
  }

  /**
   * Replace the implementation of discovered sbt plugins so that we don't run it
   * when we `bloopGenerate` or `bloopInstall`. This is important because when there
   * are sbt plugins in the build they trigger the compilation of all the modules.
   * We do no-op when there is indeed an sbt plugin in the build. */
  def discoveredSbtPluginsSettings: Seq[Def.Setting[_]] = List(
    Keys.discoveredSbtPlugins := Def.taskDyn {
      val roots = Keys.executionRoots.value
      if (!Keys.sbtPlugin.value) inlinedTask(PluginDiscovery.emptyDiscoveredNames)
      else {
        if (roots.exists(scoped => scoped.key == BloopKeys.bloopInstall.key)) {
          inlinedTask(PluginDiscovery.emptyDiscoveredNames)
        } else {
          Def.task(PluginDiscovery.discoverSourceAll(Keys.compile.value))
        }
      }
    }.value
  )

  private final val ScalaNativePluginLabel = "scala.scalanative.sbtplugin.ScalaNativePlugin"
  private final val ScalaJsPluginLabel = "org.scalajs.sbtplugin.ScalaJSPlugin"

  private final val ScalaJsFastOpt = "fastopt"
  private final val ScalaJsFullOpt = "fullopt"

  private final val NoJSModule = "NoModule"
  private final val CommonJSModule = "CommonJSModule"

  /**
   * Create a "proxy" for a setting that will allow us to inspect its value even though
   * its not accessed from the same classloader. This is required to access Scala.js
   * settings whose return type is Scala.js-specific and only lives in their classloader.
   * Returns none if the key wasn't found with the id and type of class passed in.
   */
  def proxyForSetting(id: String, `class`: Class[_]): Def.Initialize[Option[AnyRef]] = {
    val stageManifest = new Manifest[AnyRef] { override def runtimeClass = `class` }
    toAnyRefSettingKey(id, stageManifest).?
  }

  def findOutScalaJsStage: Def.Initialize[Option[String]] = Def.settingDyn {
    try {
      val stageClass = Class.forName("org.scalajs.sbtplugin.Stage")
      val stageSetting = proxyForSetting("scalaJSStage", stageClass)
      Def.setting {
        stageSetting.value.toString match {
          case "Some(FastOpt)" => Some(ScalaJsFastOpt)
          case "Some(FullOpt)" => Some(ScalaJsFullOpt)
          case _ => None
        }
      }
    } catch {
      case _: ClassNotFoundException => Def.setting(None)
    }
  }

  def findOutScalaJsModuleKind: Def.Initialize[Option[String]] = Def.settingDyn {
    try {
      val stageClass = Class.forName("core.tools.linker.backend.ModuleKind")
      val stageSetting = proxyForSetting("scalaJSModuleKind", stageClass)
      Def.setting {
        stageSetting.value.toString match {
          case "Some(NoModule)" => Some(NoJSModule)
          case "Some(CommonJSModule)" => Some(CommonJSModule)
          case _ => None
        }
      }
    } catch {
      case _: ClassNotFoundException => Def.setting(None)
    }
  }

  def bloopTargetDir: Def.Initialize[File] = Def.setting {
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

  def generateBloopProductDirectories: Def.Initialize[File] = Def.setting {
    val configuration = Keys.configuration.value
    val bloopTarget = BloopKeys.bloopTargetDir.value
    Keys.classDirectory.?.value match {
      case Some(value) => value
      case None =>
        val bloopClassesDir = bloopTarget / (Defaults.prefix(configuration.name) + "classes")
        if (!bloopClassesDir.exists()) sbt.IO.createDirectory(bloopClassesDir)
        bloopClassesDir
    }
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

  private def distinctOn[A, B](list: Seq[A], f: A => B): List[A] = {
    list.foldLeft(List.empty[A]) { (acc, elem) =>
      val value = f(elem)
      acc.find(item => f(item) == value) match {
        case Some(_) => acc
        case None => elem :: acc
      }
    }
  }

  private def depsFromConfig(configuration: Configuration): List[Configuration] = {
    configuration :: configuration.extendsConfigs.toList.flatMap(dep => depsFromConfig(dep))
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
  def eligibleDepsFromConfig: Def.Initialize[Task[List[Configuration]]] = {
    Def.task {

      /*
       * When we resolve `Keys.configuration.value`, we don't obtain the same
       * configuration object that is passed in the following code snippet:
       *
       * ```
       * val foo = project
       *  .in(file(".") / "foo")
       *  .configs(IntegrationTest.extend(Test)) // line 2
       *  .settings(
       *    Defaults.itSettings,
       *    inConfig(IntegrationTest)( // line 3
       *      BloopDefaults.configSettings
       *    )
       *)
       * ```
       *
       * But instead obtain the configuration object in "line 3", which is
       * different from that on "line 2" because it does not extend `Test`.
       *
       * To work around this limitation, we obtain here both the config
       * in the scope and the possibly other real config from the project
       * (which we map by name) and then try to resolve dependencies and
       * add them together. Those dependencies are then the ones we process.
       *
       * We work around this problem to avoid telling the users how they
       * should be using the configuration-related sbt APIs, it's just more
       * time-efficient than correcting the wrong code out there.
       */
      val config = Keys.configuration.value
      val activeProjectConfigs = Keys.thisProject.value.configurations
      val resolvedConfig = activeProjectConfigs.find(_.name == config.name)
      val resolvedConfigDeps = resolvedConfig.toList.flatMap(depsFromConfig(_))
      val configs = distinctOn(
        depsFromConfig(config) ++ resolvedConfigDeps,
        (c: Configuration) => c.name
      ).filterNot(c => c == config || resolvedConfig.exists(_ == c))

      import scala.collection.JavaConverters._
      val data = Keys.settingsData.value
      val thisProjectRef = Keys.thisProjectRef.value
      val eligibleConfigs = activeProjectConfigs.filter { c =>
        val configKey = ConfigKey.configurationToKey(c)
        val eligibleKey = BloopKeys.bloopGenerate in (thisProjectRef, configKey)
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
      }.toSet

      configs.filter(c => eligibleConfigs.contains(c))
    }
  }

  /**
   * Keep a map of all the project names registered in this build load.
   *
   * This map is populated in [[bloopInstall]] before [[bloopGenerate]] is run,
   * which means that by the time [[projectNameFromString]] runs this map will
   * already contain an updated list of all the projects in the build.
   *
   * This information is paramount so that we don't generate a project with
   * the same name of a valid user-facing project. For example, if a user
   * defines two projects named `foo` and `foo-test`, we need to make sure
   * that the test configuration for `foo`, mapped to `foo-test` does not collide
   * with the compile configuration of `foo-test`.
   */
  private final val allProjectNames = new scala.collection.mutable.HashSet[String]()

  /** Cache any replacement that has happened to a project name. */
  private final val projectNameReplacements =
    new java.util.concurrent.ConcurrentHashMap[String, String]()

  def projectNameFromString(name: String, configuration: Configuration, logger: Logger): String = {
    if (configuration == Compile) name
    else {
      val supposedName = s"$name-${configuration.name}"
      // Let's check if the default name (with no append of anything) is not used by another project
      if (!allProjectNames.contains(supposedName)) supposedName
      else {
        val existingReplacement = projectNameReplacements.get(supposedName)
        if (existingReplacement != null) existingReplacement
        else {
          // Use `+` instead of `-` as separator and report the user about the change
          val newUnambiguousName = s"$name+${configuration.name}"
          projectNameReplacements.computeIfAbsent(
            supposedName,
            new java.util.function.Function[String, String] {
              override def apply(supposedName: String): String = {
                logger.warn(
                  s"Derived target name '${supposedName}' already exists in the build, changing to ${newUnambiguousName}"
                )
                newUnambiguousName
              }
            }
          )
        }
      }
    }
  }

  import sbt.{Settings, Scope}
  private def getConfigurations(
      p: sbt.ResolvedReference,
      data: Settings[Scope]
  ): Seq[Configuration] = Keys.ivyConfigurations.in(p).get(data).getOrElse(Nil)

  private val disabledSbtConfigurationNames: List[String] =
    List(sbt.Runtime, sbt.Default, sbt.Provided, sbt.Optional).map(_.name)

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
      activeProjectConfigurationNames: Seq[String],
      supportedConfigurationNames: Seq[String],
      data: sbt.Settings[sbt.Scope],
      logger: Logger
  ): List[String] = {
    // We only detect dependencies for those configurations that are supported
    def filterSupported(configurationNames: Seq[String]): Seq[String] = {
      configurationNames.filter(conf => supportedConfigurationNames.exists(_ == conf))
    }

    val ref = dep.project
    val activeDependentConfigurations = getConfigurations(ref, data)
    dep.configuration match {
      case Some(_) =>
        val mapping = sbt.Classpaths.mapped(
          dep.configuration,
          filterSupported(activeProjectConfigurationNames),
          filterSupported(activeDependentConfigurations.map(_.name)),
          "compile",
          "*->compile"
        )

        mapping(configuration.name) match {
          case Nil => Nil
          case configurationNames =>
            val configurations = configurationNames.iterator
              .flatMap(name => activeDependentConfigurations.find(_.name == name).toList)
              .toList

            val allDependentConfigurations = configurations
              .flatMap(c => depsFromConfig(c).filterNot(_ == c))
              .filterNot(c => disabledSbtConfigurationNames.contains(c.name))

            val validDependentConfigurations = {
              allDependentConfigurations.flatMap { dc =>
                val isValid = supportedConfigurationNames.contains(dc.name)
                if (isValid) List(dc)
                else {
                  logger.warn(Feedback.unknownConfigurations(project, List(dc.name), ref))
                  // Use test dependency as the conservative default if a configuration is unknown
                  List(projectNameFromString(ref.project, Test, logger))
                }
              }.toSet
            }

            // A dependency cannot depend on each other, so this way of computing the roots works
            val rootDependencies =
              configurations.filterNot(c => validDependentConfigurations.contains(c))
            rootDependencies.map(c => projectNameFromString(ref.project, c, logger)).distinct
        }
      case None =>
        // If no configuration, default is `Compile` dependency (see scripted tests `cross-compile-test-configuration`)
        List(projectNameFromString(ref.project, Compile, logger))
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
      case ProjectRef(_, project) => project
      case RootProject(_) => "root"
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
      opts: List[String],
      internalClasspath: Seq[(File, File)],
      logger: Logger
  ): List[String] = {
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
    if (!Files.isRegularFile(presumedChecksum)) None
    else {
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
      ms1.find(
        m => m0.organization == m.organization && m0.name == m.name && m0.version == m.version
      ) match {
        case Some(m1) => m0.copy(artifacts = m0.artifacts ++ m1.artifacts)
        case None => m0
      }
    }.distinct
  }

  def onlyCompilationModules(ms: Seq[Config.Module], classpath: List[Path]): Seq[Config.Module] = {
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
    val runUpdateClassifiers = BloopKeys.bloopExportJarClassifiers.value.nonEmpty
    if (!runUpdateClassifiers) Def.task(None)
    else if (BloopKeys.bloopIsMetaBuild.value) Def.task(Some(Keys.updateSbtClassifiers.value))
    else Def.task(Some(Keys.updateClassifiers.value))
  }

  /** Find nativelib jar on the classpath. Copy pasted from Scala native. */
  def findNativelib(classpath: Seq[Path]): Option[Path] = {
    classpath.find { path =>
      val absolute = path.toAbsolutePath.toString
      absolute.contains("scala-native") && absolute.contains("nativelib")
    }
  }

  import sbt.ModuleID
  private final val CompilerPluginConfig = "plugin->default(compile)"

  /** Find nativelib jar on the classpath. Copy pasted from Scala native. */
  def findVersion(deps: Seq[ModuleID], org: String): Option[String] = {
    def isPlugin(d: ModuleID, org: String) =
      d.configurations.toList.contains(CompilerPluginConfig) && d.organization == org
    deps.find(isPlugin(_, org)).map(_.revision)
  }

  private val isWindows: Boolean =
    System.getProperty("os.name").toLowerCase(java.util.Locale.ENGLISH).contains("windows")

  lazy val findOutPlatform: Def.Initialize[Task[Config.Platform]] = Def.taskDyn {
    val project = Keys.thisProject.value
    val (javaHome, javaOptions) = javaConfiguration.value
    val mainClass = BloopKeys.bloopMainClass.in(Keys.run).value

    val libraryDeps = Keys.libraryDependencies.value
    val externalClasspath: Seq[Path] =
      Keys.externalDependencyClasspath.value.map(_.data.toPath).filter(f => Files.exists(f))

    val pluginLabels = project.autoPlugins.map(_.label).toSet

    // FORMAT: OFF
    if (pluginLabels.contains(ScalaNativePluginLabel)) {
      if (isWindows) {
        // Default on jvm config because native is not supported in Windows yet
        Def.task(Config.Platform.default)
      } else {
        Def.task {
          // Add targetTriple to the config when the scala native plugin supports it
          val emptyNative = Config.NativeConfig.empty
          val clang = ScalaNativeKeys.nativeClang.?.value.map(_.toPath).getOrElse(emptyNative.clang)
          val clangpp = ScalaNativeKeys.nativeClangPP.?.value.map(_.toPath).getOrElse(emptyNative.clangpp)
          val nativeGc = ScalaNativeKeys.nativeGC.?.value.getOrElse(emptyNative.gc)
          val nativeLinkStubs = ScalaNativeKeys.nativeLinkStubs.?.value.getOrElse(emptyNative.linkStubs)
          val nativelib = findNativelib(externalClasspath).getOrElse(emptyNative.nativelib)
          val nativeCompileOptions = ScalaNativeKeys.nativeCompileOptions.?.value.toList.flatten
          val nativeLinkingOptions = ScalaNativeKeys.nativeLinkingOptions.?.value.toList.flatten
          val nativeVersion = findVersion(libraryDeps, "org.scala-native").getOrElse(emptyNative.version)

          val nativeMode = ScalaNativeKeys.nativeMode.?.value match {
            case Some("debug") => Config.LinkerMode.Debug
            case Some("release") => Config.LinkerMode.Release
            case _ => emptyNative.mode
          }

          val options = Config.NativeOptions(nativeLinkingOptions, nativeCompileOptions)
          val nativeConfig = Config.NativeConfig(nativeVersion, nativeMode, nativeGc, emptyNative.targetTriple, nativelib, clang, clangpp, Nil, options, nativeLinkStubs, None)
          Config.Platform.Native(nativeConfig, mainClass)
        }
      }
    } else if (pluginLabels.contains(ScalaJsPluginLabel)) {
      Def.task {
        val emptyScalaJs = Config.JsConfig.empty
        val scalaJsVersion = findVersion(libraryDeps, "org.scala-js").getOrElse(emptyScalaJs.version)
        val scalaJsStage = BloopKeys.bloopScalaJSStage.value match {
          case Some(ScalaJsFastOpt) => Config.LinkerMode.Debug
          case Some(ScalaJsFullOpt) => Config.LinkerMode.Release
          case _ => emptyScalaJs.mode
        }

        val scalaJsModule = BloopKeys.bloopScalaJSModuleKind.value match {
          case Some(NoJSModule) => Config.ModuleKindJS.NoModule
          case Some(CommonJSModule) => Config.ModuleKindJS.CommonJSModule
          case _ => emptyScalaJs.kind
        }

        val scalaJsEmitSourceMaps =
          ScalaJsKeys.scalaJSEmitSourceMaps.?.value.getOrElse(emptyScalaJs.emitSourceMaps)
        val jsdom = Some(false)
        val jsConfig = Config.JsConfig(scalaJsVersion, scalaJsStage, scalaJsModule, scalaJsEmitSourceMaps, jsdom, None, None, emptyScalaJs.toolchain)
        Config.Platform.Js(jsConfig, mainClass)
      }
    } else {
      Def.task {
        val config = Config.JvmConfig(Some(javaHome.toPath), javaOptions.toList)
        Config.Platform.Jvm(config, mainClass)
      }
    }
    // FORMAT: ON
  }

  case class SbtMetadata(base: File, config: Config.Sbt)
  lazy val computeSbtMetadata: Def.Initialize[Task[Option[SbtMetadata]]] = Def.taskDyn {
    val isMetaBuild = BloopKeys.bloopIsMetaBuild.value
    if (!isMetaBuild) Def.task(None)
    else {
      Def.task {
        val structure = Keys.buildStructure.value
        val buildUnit = structure.units(structure.root)
        val sbtVersion = Keys.sbtVersion.value
        val plugins = buildUnit.unit.plugins
        val data = Keys.pluginData.value

        // We do what sbt.Load does here: discover all plugins to find out the auto imports
        val initialPluginLoader = new PluginManagement.PluginClassLoader(plugins.loader)
        initialPluginLoader.add(data.classpath.map(_.data.toURI.toURL))
        val autoImports = PluginDiscovery.discoverAll(data, initialPluginLoader).imports.toList
        Some(SbtMetadata(plugins.base, Config.Sbt(sbtVersion, autoImports)))
      }
    }
  }

  // Mostly used by the sbt-scripted tests
  def unsafeParseConfig(jsonConfig: Path): Config.File = {
    bloop.config.read(jsonConfig) match {
      case Right(config) => config
      case Left(t) =>
        System.err.println(s"Unexpected error when parsing $jsonConfig: ${t.getMessage}, skipping!")
        throw t
    }
  }

  def safeParseConfig(jsonConfig: Path, logger: Logger): Option[Config.File] = {
    bloop.config.read(jsonConfig) match {
      case Right(config) => Some(config)
      case Left(t) =>
        logger.error(s"Unexpected error when parsing $jsonConfig: ${t.getMessage}, skipping!")
        logger.trace(t)
        None
    }
  }

  lazy val bloopTargetName: Def.Initialize[String] = Def.setting {
    val logger = Keys.sLog.value
    val project = Keys.thisProject.value
    val configuration = Keys.configuration.value
    projectNameFromString(project.id, configuration, logger)
  }

  case class GeneratedProject(
      outPath: Path,
      project: Config.Project,
      fromSbtUniverseId: String
  )

  private[bloop] val targetNamesToPreviousConfigs =
    new ConcurrentHashMap[String, GeneratedProject]()
  private[bloop] val targetNamesToConfigs =
    new ConcurrentHashMap[String, GeneratedProject]()

  def bloopGenerate: Def.Initialize[Task[Option[File]]] = Def.taskDyn {
    val logger = Keys.streams.value.log
    val project = Keys.thisProject.value
    val scoped = Keys.resolvedScoped.value
    val configuration = Keys.configuration.value
    val isMetaBuild = BloopKeys.bloopIsMetaBuild.value
    val hasConfigSettings = productDirectoriesUndeprecatedKey.?.value.isDefined
    val projectName = projectNameFromString(project.id, configuration, logger)
    val currentSbtUniverse = BloopKeys.bloopGlobalUniqueId.value

    lazy val generated = Option(targetNamesToConfigs.get(projectName))
    if (isMetaBuild && configuration == Test) inlinedTask[Option[File]](None)
    else if (!hasConfigSettings) inlinedTask[Option[File]](None)
    else if (generated.isDefined && generated.get.fromSbtUniverseId == currentSbtUniverse) {
      Def.task {
        // Force classpath to force side-effects downstream to fully simulate `bloopGenerate`
        val _ = emulateDependencyClasspath.value.map(_.toPath.toAbsolutePath).toList
        generated.map(_.outPath.toFile)
      }
    } else {
      Def
        .task {
          val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
          val buildBaseDirectory = Keys.baseDirectory.in(ThisBuild).value.getAbsoluteFile
          val rootBaseDirectory = new File(Keys.loadedBuild.value.root)

          val bloopConfigDir = BloopKeys.bloopConfigDir.value
          sbt.IO.createDirectory(bloopConfigDir)
          val outFile = bloopConfigDir / s"$projectName.json"
          val outFilePath = outFile.toPath

          // Important that it's lazy, we want to avoid the price of reading config file if possible
          lazy val previousConfigFile = {
            if (!Files.exists(outFilePath)) None
            else {
              safeParseConfig(outFilePath, logger).map(_.project)
            }
          }

          val dependencies = {
            val data = Keys.settingsData.value
            val projectRef = Keys.thisProjectRef.value
            val projectConfigurationNames = getConfigurations(projectRef, data).map(_.name)
            val supportedConfigurationNames =
              BloopKeys.bloopSupportedConfigurations.value.map(_.name)
            // Project dependencies come from classpath deps and also inter-project config deps
            val classpathProjectDependencies = project.dependencies.flatMap { d =>
              projectDependencyName(
                d,
                configuration,
                project,
                projectConfigurationNames,
                supportedConfigurationNames,
                data,
                logger
              )
            }

            val configDependencies =
              eligibleDepsFromConfig.value.map(c => projectNameFromString(project.id, c, logger))
            logger.debug(s"[${projectName}] Classpath dependencies ${classpathProjectDependencies}")
            logger.debug(s"[${projectName}] Dependencies from configurations ${configDependencies}")

            // The distinct here is important to make sure that there are no repeated project deps
            (classpathProjectDependencies ++ configDependencies).distinct.toList
          }

          // Aggregates are considered to be dependencies too for the sake of user-friendliness
          val aggregates =
            project.aggregate.map(agg => projectNameFromString(agg.project, configuration, logger))
          val dependenciesAndAggregates = (dependencies ++ aggregates).distinct

          val out = (bloopConfigDir / project.id).toPath.toAbsolutePath
          val scalaName = "scala-compiler"
          val scalaVersion = Keys.scalaVersion.value
          val scalaOrg = Keys.ivyScala.value.map(_.scalaOrganization).getOrElse("org.scala-lang")
          val allScalaJars = Keys.scalaInstance.value.allJars.map(_.toPath.toAbsolutePath).toList

          val classesDir = BloopKeys.bloopProductDirectories.value.head.toPath()
          val classpath = emulateDependencyClasspath.value.map(_.toPath.toAbsolutePath).toList

          /* This is a best-effort to export source directories + stray source files that
           * are not contained in them. Source directories are superior over source files because
           * they allow us to watch them and detect the creation of new source files in situ. */
          val sources = {
            val sourceDirs = Keys.sourceDirectories.value.map(_.toPath)
            val sourceFiles = pruneSources(sourceDirs, Keys.sources.value.map(_.toPath))
            (sourceDirs ++ sourceFiles).toList
          }

          val testOptions = {
            val frameworks =
              Keys.testFrameworks.value
                .map(f => Config.TestFramework(f.implClassNames.toList))
                .toList
            val options = Keys.testOptions.value.foldLeft(Config.TestOptions.empty) {
              case (options, sbt.Tests.Argument(framework0, args0)) =>
                val args = args0.toList
                val framework = framework0.map(f => Config.TestFramework(f.implClassNames.toList))
                options.copy(arguments = Config.TestArgument(args, framework) :: options.arguments)
              case (options, sbt.Tests.Exclude(tests)) =>
                options.copy(excludes = tests.toList ++ options.excludes)
              case (options, other: sbt.TestOption) =>
                logger.info(s"Skipped test option '$other' as it can only be used within sbt")
                options
            }
            Config.Test(frameworks, options)
          }

          val javacOptions = Keys.javacOptions.in(Keys.compile).in(configuration).value.toList
          val scalacOptions = {
            val options = Keys.scalacOptions.in(Keys.compile).in(configuration).value.toList
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
          val platform = findOutPlatform.value

          val binaryModules = configModules(Keys.update.value)
          val sourceModules = {
            val sourceModulesFromSbt = updateClassifiers.value.toList.flatMap(configModules)
            if (sourceModulesFromSbt.nonEmpty) sourceModulesFromSbt
            else previousConfigFile.flatMap(_.resolution.map(_.modules)).toList.flatten
          }

          val allModules = mergeModules(binaryModules, sourceModules)
          val resolution = {
            val modules = onlyCompilationModules(allModules, classpath).toList
            if (modules.isEmpty) None else Some(Config.Resolution(modules))
          }

          // Force source generators on this task manually
          Keys.managedSources.value

          // format: OFF
          val config = {
            val c = Keys.classpathOptions.value
            val java = Config.Java(javacOptions)
            val analysisOut = BloopKeys.bloopAnalysisOut.value.map(_.toPath)//.getOrElse(out.resolve(s"${projectName}-analysis.bin"))
            val compileSetup = Config.CompileSetup(compileOrder, c.bootLibrary, c.compiler, c.extra, c.autoBoot, c.filterLibrary)
            val `scala` = Config.Scala(scalaOrg, scalaName, scalaVersion, scalacOptions, allScalaJars, analysisOut, Some(compileSetup))
            val resources = Some(bloopResourcesTask.value)

            val sbt = computeSbtMetadata.value.map(_.config)
            val project = Config.Project(projectName, baseDirectory, Option(buildBaseDirectory.toPath), sources, dependenciesAndAggregates,
              classpath, out, classesDir, resources, Some(`scala`), Some(java), sbt, Some(testOptions), Some(platform), resolution)
            Config.File(Config.File.LatestVersion, project)
          }
          // format: ON

          bloop.config.write(config, outFile.toPath)

          // Only shorten path for configuration files written to the the root build
          val allInRoot = BloopKeys.bloopAggregateSourceDependencies.in(Global).value
          val userFriendlyConfigPath = {
            if (allInRoot || buildBaseDirectory == rootBaseDirectory)
              outFile.relativeTo(rootBaseDirectory).getOrElse(outFile)
            else outFile
          }

          targetNamesToConfigs
            .put(projectName, GeneratedProject(outFile.toPath, config.project, currentSbtUniverse))

          logger.debug(s"Bloop wrote the configuration of project '$projectName' to '$outFile'")
          logger.success(s"Generated $userFriendlyConfigPath")
          Some(outFile)
        }
    }
  }

  private final val allJson = sbt.GlobFilter("*.json")
  private final val removeStaleProjects = {
    allConfigDirs: Set[File] =>
      { (state: State, generatedFiles: Set[Option[File]]) =>
        val logger = state.globalLogging.full
        val allConfigs =
          allConfigDirs.flatMap(configDir => sbt.PathFinder(configDir).*(allJson).get)
        allConfigs.diff(generatedFiles.flatMap(_.toList)).foreach { configFile =>
          if (configFile.getName() == "bloop.settings.json") ()
          else {
            sbt.IO.delete(configFile)
            logger.warn(s"Removed stale $configFile")
          }
        }
        state
      }
  }

  def bloopGlobalUniqueIdTask: Def.Initialize[String] = Def.setting {
    // Create a new instance of any class, gets its hash code and stringify it to get a global id
    (new scala.util.Random().hashCode()).toString
  }

  def bloopInstall: Def.Initialize[Task[Unit]] = Def.taskDyn {
    val filter = sbt.ScopeFilter(
      sbt.inAnyProject,
      sbt.inAnyConfiguration,
      sbt.inTasks(BloopKeys.bloopGenerate)
    )

    // Clear the global map of available names and add the ones detected now
    val loadedBuild = Keys.loadedBuild.value
    allProjectNames.clear()
    projectNameReplacements.clear()
    allProjectNames.++=(loadedBuild.allProjectRefs.map(_._1.project))

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

  private def wrapWithInitialize[T](value: Task[T]): Def.Initialize[Task[T]] = Def.value(value)
  private def inlinedTask[T](value: T): Def.Initialize[Task[T]] = Def.toITask(Def.value(value))

  /**
   * Emulates `dependencyClasspath` without triggering compilation of dependent projects.
   *
   * Why do we do this instead of a simple `productDirectories ++ libraryDependencies`?
   * We want the classpath to have the correct topological order of the project dependencies.
   */
  final def bloopInternalDependencyClasspath: Def.Initialize[Task[Seq[(File, File)]]] = {
    Def.taskDyn {
      val currentProject = Keys.thisProjectRef.value
      val data = Keys.settingsData.value
      val deps = Keys.buildDependencies.value
      val self = Keys.configuration.value

      Keys.classpathConfiguration.?.value match {
        case Some(conf) =>
          import scala.collection.JavaConverters._
          val visited = Classpaths.interSort(currentProject, conf, data, deps)
          val productDirs = (new java.util.LinkedHashSet[Task[Seq[File]]]).asScala
          val bloopProductDirs = (new java.util.LinkedHashSet[Task[Seq[File]]]).asScala
          for ((dep, c) <- visited) {
            if ((dep != currentProject) || (conf.name != c && self.name != c)) {
              val classpathKey = productDirectoriesUndeprecatedKey in (dep, sbt.ConfigKey(c))
              if (classpathKey.get(data).isEmpty) ()
              else {
                productDirs += classpathKey.get(data).getOrElse(sbt.std.TaskExtra.constant(Nil))
                val bloopKey = BloopKeys.bloopProductDirectories in (dep, sbt.ConfigKey(c))
                bloopProductDirs += bloopKey.get(data).getOrElse(sbt.std.TaskExtra.constant(Nil))
              }
            }
          }

          wrapWithInitialize(
            productDirs.toList.join.map(_.flatten.distinct).flatMap { a =>
              bloopProductDirs.toList.join.map(_.flatten.distinct).map { b =>
                a.zip(b)
              }
            }
          )
        case None => inlinedTask(Nil)
      }

    }
  }

  def emulateDependencyClasspath: Def.Initialize[Task[Seq[File]]] = Def.task {
    val internalClasspath = BloopKeys.bloopInternalClasspath.value.map(_._2)
    val externalClasspath = Keys.externalDependencyClasspath.value.map(_.data)
    internalClasspath ++ externalClasspath
  }

  /**
   * Forces resource generators outside of `bloopGenerate`, details explained
   * in `bloopResourcesTask`. The following task writes again into the bloop
   * configuration file in case a resource that has been generated falls
   * outside the sbt-defined resource directories. This is rare but Bloop
   * handles this two-step configuration phase by always reading the bloop
   * configuration file before compiling *and* before running and testing.
   *
   * Note that this task is triggered by `bloopGenerate`, which means we will
   * run it when `bloopGenerate` has finished its execution.
   */
  def bloopForceResourceGenerators: Def.Initialize[Task[Unit]] = {
    Def
      .taskDyn {
        val name = BloopKeys.bloopTargetName.value
        val logger = Keys.streams.value.log
        val configuration = Keys.configuration.value
        val generated = Option(targetNamesToConfigs.get(name))
        val hasConfigSettings = productDirectoriesUndeprecatedKey.?.value.isDefined
        if (!hasConfigSettings || generated.isEmpty) inlinedTask(())
        else {
          logger.debug(s"Forcing resource generators for target $name")
          Def.task {
            val generated = targetNamesToConfigs.get(name)
            val currentResources = generated.project.resources.toList.flatten
            val currentResourceDirs = currentResources.filter(Files.isDirectory(_))
            val allResourceFiles = Keys.resources.in(configuration).value
            val additionalResources =
              ConfigUtil.pathsOutsideRoots(currentResourceDirs, allResourceFiles.map(_.toPath))
            if (additionalResources.isEmpty) ()
            else {
              val missingResources =
                additionalResources.filter(resource => !currentResources.contains(resource))
              if (missingResources.nonEmpty) {
                val newResources = Some(currentResources ++ missingResources)
                val newProject = generated.project.copy(resources = newResources)
                val newConfig = Config.File(Config.File.LatestVersion, newProject)
                // Copy somewhere else and then move to make an atomic replacement
                val tempConfigFile =
                  Files.createTempFile("bloop-config", generated.outPath.getFileName.toString)
                bloop.config.write(newConfig, tempConfigFile)
                Files.move(tempConfigFile, generated.outPath)
                ()
              }
            }
          }
        }
      }
      .triggeredBy(BloopKeys.bloopGenerate)
  }

  /**
   * Extract unmanaged resources files and {managed,unmanaged} resource
   * directories out of the project. Avoid forcing `Keys.resources` and
   * `Keys.managedResources` because task generators can often trigger
   * compilation of this project and dependents. This is problematic if the
   * task that triggered the bloop generation is `Keys.compile` as it creates a
   * recursive dependency that hangs the sbt execution engine. To avoid this,
   * we force `Keys.resourceGenerators` in another task `triggeredBy` the *
   * `bloopGenerate` task, hence breaking the recursive dependency. See
   * [[bloopForceResourceGenerators]] for more information on how that works.
   */
  def bloopResourcesTask: Def.Initialize[Task[List[Path]]] = {
    Def.taskDyn {
      val configKey = sbt.ConfigKey(Keys.configuration.value.name)
      Def.task {
        import sbt._
        val s = Keys.streams.value
        val bloopClassDir = BloopKeys.bloopClassDirectory.value
        val resourceDirs = Classpaths
          .concatSettings(
            Keys.unmanagedResourceDirectories.in(configKey),
            Keys.managedResourceDirectories.in(configKey)
          )
          .value
          .map(_.toPath)

        val unmanagedResourceFiles = Keys.unmanagedResources.in(configKey).value
        val additionalResources =
          ConfigUtil.pathsOutsideRoots(resourceDirs, unmanagedResourceFiles.map(_.toPath))
        (resourceDirs ++ additionalResources).toList
      }
    }
  }

  private type JavaConfiguration = (File, Seq[String])

  /**
   * Extract the information that we need to configure forking for run or test.
   */
  def javaConfiguration: Def.Initialize[Task[JavaConfiguration]] = Def.taskDyn {
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
