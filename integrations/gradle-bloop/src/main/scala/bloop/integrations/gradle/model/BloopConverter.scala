package bloop.integrations.gradle.model

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

import bloop.config.Config
import bloop.config.Config.{JvmConfig, Platform, TestArgument, TestOptions}
import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.model.BloopConverter.SourceSetDep
import bloop.integrations.gradle.syntax._
import org.gradle.api.{GradleException, Project}
import org.gradle.api.artifacts._
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.artifacts.publish.ArchivePublishArtifact
import org.gradle.api.internal.file.copy.DefaultCopySpec
import org.gradle.api.internal.tasks.DefaultSourceSetOutput
import org.gradle.api.internal.tasks.compile.{DefaultJavaCompileSpec, JavaCompilerArgumentsBuilder}
import org.gradle.api.plugins.{ApplicationPluginConvention, JavaPluginConvention}
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.{AbstractCopyTask, SourceSet, SourceSetOutput}
import org.gradle.api.tasks.compile.{CompileOptions, JavaCompile}
import org.gradle.api.tasks.scala.{ScalaCompile, ScalaCompileOptions}
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.gradle.plugins.ide.internal.tooling.java.DefaultInstalledJdk

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Define the conversion from Gradle's project model to Bloop's project model.
 *
 * @param parameters Parameters provided by Gradle's user configuration
 */
final class BloopConverter(parameters: BloopParameters) {

  /**
   * Converts a project's given source set to a Bloop project
   *
   * Bloop analysis output will be targetDir/project-name/project[-sourceSet].bin
   *
   * Output classes are generated to projectDir/build/classes/scala/sourceSetName to
   * be compatible with Gradle.
   *
   * NOTE: Java classes will be also put into the above defined directory, not as with Gradle
   *
   * @param strictProjectDependencies Additional dependencies that cannot be inferred from Gradle's object model
   * @param project The Gradle project model
   * @param sourceSet The source set to convert
   * @param targetDir Target directory for bloop files
   * @return Bloop configuration
   */
  def toBloopConfig(
      strictProjectDependencies: List[SourceSetDep],
      project: Project,
      sourceSet: SourceSet,
      targetDir: File
  ): Try[Config.File] = {

    val resources = getResources(sourceSet)
    val sources = getSources(sourceSet).filterNot(resources.contains)

    val isTestSourceSet = sourceSet.getName == SourceSet.TEST_SOURCE_SET_NAME

    // Gradle always creates a main and test source set regardless of whether they are needed.
    // ignore test sourceset if there are no sources or resources
    if (isTestSourceSet &&
        !sources.exists(_.toFile.exists()) &&
        !resources.exists(_.toFile.exists())) {
      Failure(new GradleException("Test project has no source so ignore it"))
    } else {
      val compileClassPathConfiguration =
        project.getConfiguration(sourceSet.getCompileClasspathConfigurationName)

      // get runtime classpath.  Config name has changed over Gradle versions so check both
      val runtimeClassPathConfiguration = {
        val runtimeConfig = project.getConfiguration(sourceSet.getRuntimeConfigurationName)
        val runtimeClassPathConfig =
          project.getConfiguration(sourceSet.getRuntimeConfigurationName + "Classpath")
        if (runtimeClassPathConfig != null)
          runtimeClassPathConfig
        else
          runtimeConfig
      }

      // create a map of all Gradle Java projects and their sourcesets
      val allSourceSetsToProjects = project.getRootProject.getAllprojects.asScala
        .filter(isJavaProject)
        .flatMap(p => p.allSourceSets.map(_ -> p))
        .toMap

      // retrieve direct project dependencies.
      // Bloop doesn't require transitive project dependencies
      val compileProjectDependencies =
        getProjectDependencies(allSourceSetsToProjects, compileClassPathConfiguration)
      val runtimeProjectDependencies =
        getProjectDependencies(allSourceSetsToProjects, runtimeClassPathConfiguration)

      // Strict project dependencies should have more priority than regular project dependencies
      val allDependencies = (strictProjectDependencies.map(_.bloopModuleName) ++
        compileProjectDependencies ++ runtimeProjectDependencies).distinct

      // retrieve project dependencies recursively to include transitive project dependencies
      // Bloop requires this for the classpath
      val allCompileProjectDependencies = getProjectDependenciesRecursively(
        compileClassPathConfiguration)
      val allRuntimeProjectDependencies = getProjectDependenciesRecursively(
        runtimeClassPathConfiguration)

      // retrieve all artifacts (includes transitive) + file/dir dependencies
      val compileArtifacts: List[ResolvedArtifact] =
        compileClassPathConfiguration.getResolvedConfiguration.getResolvedArtifacts.asScala.toList
      val runtimeArtifacts: List[ResolvedArtifact] =
        runtimeClassPathConfiguration.getResolvedConfiguration.getResolvedArtifacts.asScala.toList

      // get all configurations dependencies - these go into the resolutions as the user can create their own config dependencies (e.g. compiler plugin jar)
      // some configs aren't allowed to be resolved - hence the catch
      // this can bring too many artifacts into the resolution section (e.g. junit on main projects) but there's no way to know which artifact is required by which sourceset
      val additionalArtifacts = project.getConfigurations.asScala
        .flatMap(c => {
          try {
            c.getResolvedConfiguration.getResolvedArtifacts.asScala.toSet
          } catch {
            case _: Exception => Set.empty[ResolvedArtifact]
          }
        })
        .toSet
        .filter(resolvedArtifact =>
          !isProjectDependency(allCompileProjectDependencies, resolvedArtifact) &&
            !isProjectDependency(allCompileProjectDependencies, resolvedArtifact))

      // convert artifacts to class dirs for projects and file paths for non-projects
      val compileClasspathItems = compileArtifacts.map(
        resolvedArtifact =>
          convertToPath(
            allSourceSetsToProjects,
            targetDir,
            allCompileProjectDependencies,
            resolvedArtifact))
      val runtimeClasspathItems = runtimeArtifacts.map(
        resolvedArtifact =>
          convertToPath(
            allSourceSetsToProjects,
            targetDir,
            allRuntimeProjectDependencies,
            resolvedArtifact))

      // retrieve all file/dir dependencies (includes transitive?)
      val compileArtifactFiles: Set[File] =
        compileClassPathConfiguration.getResolvedConfiguration
          .getFiles(Specs.SATISFIES_ALL)
          .asScala
          .toSet
      val runtimeArtifactFiles: Set[File] =
        runtimeClassPathConfiguration.getResolvedConfiguration
          .getFiles(Specs.SATISFIES_ALL)
          .asScala
          .toSet

      val compileClasspathFilesAsPaths = compileArtifactFiles
        .filter(f => isProjectSourceSet(allSourceSetsToProjects, f))
        .map(f => getClassesDir(targetDir, dependencyToProjectName(allSourceSetsToProjects, f)))
      val runtimeClasspathFilesAsPaths = runtimeArtifactFiles
        .filter(f => isProjectSourceSet(allSourceSetsToProjects, f))
        .map(f => getClassesDir(targetDir, dependencyToProjectName(allSourceSetsToProjects, f)))

      // get non-project artifacts for resolution
      val compileNonProjectDependencies: List[ResolvedArtifact] = compileArtifacts
        .filter(resolvedArtifact =>
          !isProjectDependency(allCompileProjectDependencies, resolvedArtifact))
      val runtimeNonProjectDependencies: List[ResolvedArtifact] = runtimeArtifacts
        .filter(resolvedArtifact =>
          !isProjectDependency(allRuntimeProjectDependencies, resolvedArtifact))
      val nonProjectDependencies =
        (compileNonProjectDependencies ++ runtimeNonProjectDependencies).distinct

      /* The classes directory is independent from Gradle's because Gradle has a different classes
       * directory for Scala and Java projects, whereas Bloop doesn't (it inherited this design from
       * sbt). Therefore, to avoid any compilation/test/run issue between Gradle and Bloop, we just
       * use our own classes 'bloop' directory in the ".bloop" directory. */
      val classesDir = getClassesDir(targetDir, project, sourceSet)

      // tag runtime items to the end of the classpath until Bloop has separate compile and runtime paths
      val classpath: List[Path] =
        (strictProjectDependencies.map(_.classesDir) ++ compileClasspathItems ++ runtimeClasspathItems ++
          compileClasspathFilesAsPaths ++ runtimeClasspathFilesAsPaths).distinct

      val modules = (nonProjectDependencies.map(artifactToConfigModule(_, project)) ++
        additionalArtifacts.map(artifactToConfigModule(_, project))).distinct

      for {
        scalaConfig <- getScalaConfig(project, sourceSet, compileArtifacts)
        resolution = Config.Resolution(modules)
        bloopProject = Config.Project(
          name = getProjectName(project, sourceSet),
          directory = project.getProjectDir.toPath,
          sources = sources,
          dependencies = allDependencies,
          classpath = classpath,
          out = project.getBuildDir.toPath,
          classesDir = classesDir,
          resources = if (resources.isEmpty) None else Some(resources),
          `scala` = scalaConfig,
          java = getJavaConfig(project, sourceSet),
          sbt = None,
          test = getTestConfig(sourceSet),
          platform = getPlatform(project, sourceSet, isTestSourceSet),
          resolution = Some(resolution)
        )
      } yield Config.File(Config.File.LatestVersion, bloopProject)
    }
  }

  private def getJavaCompileOptions(project: Project, sourceSet: SourceSet): CompileOptions = {
    val javaCompileTaskName = sourceSet.getCompileTaskName("java")
    val javaCompileTask = project.getTask[JavaCompile](javaCompileTaskName)
    javaCompileTask.getOptions
  }

  def getPlatform(project: Project, sourceSet: SourceSet, isTestSourceSet: Boolean): Option[Platform] = {
    val forkOptions = getJavaCompileOptions(project, sourceSet).getForkOptions
    val projectJdkPath = Option(forkOptions.getJavaHome).map(_.toPath)

    lazy val compileJvmOptions =
      Option(forkOptions.getMemoryInitialSize).map(mem => s"-Xms$mem").toList ++
      Option(forkOptions.getMemoryMaximumSize).map(mem => s"-Xmx$mem").toList ++
      forkOptions.getJvmArgs.asScala.toList

    lazy val testTask = project.getTask[Test]("test")
    lazy val testProperties =
      for ((name, value) <- testTask.getSystemProperties.asScala.toList)
        yield s"-D$name=$value"

    lazy val testJvmOptions =
      Option(testTask.getMinHeapSize).map(mem => s"-Xms$mem").toList ++
      Option(testTask.getMaxHeapSize).map(mem => s"-Xmx$mem").toList ++
      testTask.getJvmArgs.asScala.toList ++
      testProperties

    val projectJvmOptions =
      if (isTestSourceSet) testJvmOptions
      else compileJvmOptions

    val currentJDK = DefaultInstalledJdk.current()
    val defaultJdkPath = Option(currentJDK).map(_.getJavaHome.toPath)
    val mainClass =
      project.getConvention.findPlugin(classOf[ApplicationPluginConvention]) match {
        case appPluginConvention: ApplicationPluginConvention if !isTestSourceSet =>
          Option(appPluginConvention.getMainClassName)
        case _ =>
          None
      }

    val jdkPath = projectJdkPath.orElse(defaultJdkPath)
    Some(Platform.Jvm(JvmConfig(jdkPath, projectJvmOptions), mainClass))
  }

  def getTestConfig(sourceSet: SourceSet): Option[Config.Test] = {
    if (sourceSet.getName == SourceSet.TEST_SOURCE_SET_NAME) {
      // TODO: make this configurable?
      Some(Config.Test.defaultConfiguration)
    } else {
      None
    }
  }

  def getProjectName(project: Project, sourceSet: SourceSet): String = {
    if (sourceSet.getName == SourceSet.MAIN_SOURCE_SET_NAME) {
      project.getName
    } else {
      s"${project.getName}-${sourceSet.getName}"
    }
  }

  // Gradle has removed the getConfiguration method and replaced it with getTargetConfiguration around version 4.0
  private def getTargetConfiguration(projectDependency: ProjectDependency): String = {
    try {
      val getTargetConfiguration = classOf[ProjectDependency].getMethod("getTargetConfiguration")
      val targetConfigName = getTargetConfiguration.invoke(projectDependency)
      if (targetConfigName == null)
        Dependency.DEFAULT_CONFIGURATION
      else
        targetConfigName.asInstanceOf[String]
    } catch {
      case _: NoSuchMethodException => projectDependency.getConfiguration;
    }
  }

  // find the source of the data going into an archive
  private def getSourceSet(
      allSourceSetsToProjects: Map[SourceSet, Project],
      abstractCopyTask: AbstractCopyTask): Option[SourceSet] = {
    try {
      // protected method
      val getMainSpec = classOf[AbstractCopyTask].getDeclaredMethod("getMainSpec")
      getMainSpec.setAccessible(true)
      val mainSpec = getMainSpec.invoke(abstractCopyTask)
      if (mainSpec == null)
        None
      else {
        val getSourcePaths = classOf[DefaultCopySpec].getMethod("getSourcePaths")
        val sourcePaths = getSourcePaths.invoke(mainSpec)
        sourcePaths
          .asInstanceOf[java.util.Set[Object]]
          .asScala
          .flatMap({
            case sourceSetOutput: DefaultSourceSetOutput =>
              allSourceSetsToProjects.keySet.find(p => p.getOutput == sourceSetOutput)
            case _ => None
          })
          .headOption
      }
    } catch {
      case _: NoSuchMethodException => None;
    }
  }

  private def isJavaProject(project: Project): Boolean = {
    project.getConvention.findPlugin(classOf[JavaPluginConvention]) != null
  }

  // Gradle has removed getClassesDir and replaced with getClassesDirs - version 4.0
  private def matchesOutputDir(sourceSetOutput: SourceSetOutput, outputDir: File): Boolean = {
    try {
      val getClassesDirs = classOf[SourceSetOutput].getMethod("getClassesDirs")
      val classesDirs = getClassesDirs.invoke(sourceSetOutput)
      if (classesDirs == null)
        false
      else
        classesDirs.asInstanceOf[FileCollection].getFiles.asScala.contains(outputDir)
    } catch {
      case _: NoSuchMethodException => sourceSetOutput.getClassesDir == outputDir;
    }
  }

  private def isProjectSourceSet(
      allSourceSetsToProjects: Map[SourceSet, Project],
      file: File): Boolean = {
    // check if the dependency matches any projects output dir
    allSourceSetsToProjects.keys
      .exists(ss => matchesOutputDir(ss.getOutput, file) || file == ss.getOutput.getResourcesDir)
  }

  private def isProjectSourceSet(
      allSourceSetsToProjects: Map[SourceSet, Project],
      selfResolvingDependency: SelfResolvingDependency): Boolean = {
    // check if the dependency matches any projects output dir
    val sourceOutputDirs = selfResolvingDependency.resolve().asScala
    allSourceSetsToProjects.keys
      .exists(ss =>
        sourceOutputDirs
          .exists(outputDir =>
            matchesOutputDir(ss.getOutput, outputDir) || outputDir == ss.getOutput.getResourcesDir))
  }

  private def getProjectDependencies(
      allSourceSetsToProjects: Map[SourceSet, Project],
      configuration: Configuration): List[String] = {
    // We cannot turn this into a set directly because we need the topological order for correctness
    configuration.getAllDependencies.asScala.collect {
      case projectDependency: ProjectDependency
          if isJavaProject(projectDependency.getDependencyProject) =>
        dependencyToProjectName(allSourceSetsToProjects, projectDependency)
      case selfResolvingDependency: SelfResolvingDependency
          if isProjectSourceSet(allSourceSetsToProjects, selfResolvingDependency) =>
        dependencyToProjectName(allSourceSetsToProjects, selfResolvingDependency)
    }.toList
  }

  private def getProjectDependenciesRecursively(
      configuration: Configuration): List[ProjectDependency] = {
    // We cannot turn this into a set directly because we need the topological order for correctness
    configuration.getAllDependencies.asScala
      .collect {
        case projectDependency: ProjectDependency
            if isJavaProject(projectDependency.getDependencyProject) =>
          val depProject = projectDependency.getDependencyProject
          val depConfigName = getTargetConfiguration(projectDependency)
          val depConfig = depProject.getConfigurations.getByName(depConfigName)
          projectDependency +: getProjectDependenciesRecursively(depConfig)
      }
      .toList
      .flatten
      .distinct
  }

  def getClassesDir(targetDir: File, projectName: String): Path =
    (targetDir / projectName / "build" / "classes").toPath

  def getClassesDir(targetDir: File, project: Project, sourceSet: SourceSet): Path =
    getClassesDir(targetDir, getProjectName(project, sourceSet))

  private def getSources(sourceSet: SourceSet): List[Path] =
    sourceSet.getAllSource.getSrcDirs.asScala.map(_.toPath).toList

  private def getResources(sourceSet: SourceSet): List[Path] =
    sourceSet.getResources.getSrcDirs.asScala.map(_.toPath).toList

  private def getSourceSet(
      allSourceSetsToProjects: Map[SourceSet, Project],
      projectDependency: ProjectDependency): SourceSet = {
    // use configuration to find which is the correct source set
    val depProject = projectDependency.getDependencyProject
    val configurationName = getTargetConfiguration(projectDependency)
    val configuration = depProject.getConfiguration(configurationName)

    // test for jar references
    val archiveTasks = configuration.getArtifacts.asScala.collect {
      case archivePublishArtifact: ArchivePublishArtifact => archivePublishArtifact.getArchiveTask
    }
    val possibleArchiveSourceSet =
      archiveTasks.flatMap(archiveTask => getSourceSet(allSourceSetsToProjects, archiveTask))

    possibleArchiveSourceSet.headOption.getOrElse({
      // no archive source set so revert to default project references
      // find intersection of configuration and source set - maintain hierarchy order
      // this attempts to check if a non-default source set is referenced
      val configurationHierarchy = configuration.getHierarchy.iterator.asScala.toList
      val sourceSetToConfigNames = depProject.allSourceSets
        .flatMap(
          s =>
            List(
              s.getCompileClasspathConfigurationName,
              s.getRuntimeConfigurationName,
              s.getRuntimeConfigurationName + "Classpath").map(name => name -> s))
        .toMap

      // take first source set that matches the configuration or default to main
      configurationHierarchy
        .flatMap(c => sourceSetToConfigNames.get(c.getName))
        .headOption
        .getOrElse(depProject.getSourceSet(SourceSet.MAIN_SOURCE_SET_NAME))
    })
  }

  private def dependencyToProjectName(
      allSourceSetsToProjects: Map[SourceSet, Project],
      selfResolvingDependency: SelfResolvingDependency): String = {
    val sourceOutputDirs = selfResolvingDependency.resolve().asScala
    allSourceSetsToProjects
      .find(
        ss =>
          sourceOutputDirs.exists(ss2 => matchesOutputDir(ss._1.getOutput, ss2)) ||
            sourceOutputDirs.contains(ss._1.getOutput.getResourcesDir))
      .map(ss => getProjectName(ss._2, ss._1))
      .get
  }

  private def dependencyToProjectName(
      allSourceSetsToProjects: Map[SourceSet, Project],
      file: File): String = {
    allSourceSetsToProjects
      .find(
        ss =>
          matchesOutputDir(ss._1.getOutput, file) ||
            file == ss._1.getOutput.getResourcesDir)
      .map(ss => getProjectName(ss._2, ss._1))
      .get
  }

  private def dependencyToProjectName(
      allSourceSetsToProjects: Map[SourceSet, Project],
      projectDependency: ProjectDependency): String = {
    val depProject = projectDependency.getDependencyProject
    getProjectName(depProject, getSourceSet(allSourceSetsToProjects, projectDependency))
  }

  private def dependencyToClassPath(
      allSourceSetsToProjects: Map[SourceSet, Project],
      targetDir: File,
      projectDependency: ProjectDependency): Path = {
    val depProject = projectDependency.getDependencyProject
    getClassesDir(targetDir, depProject, getSourceSet(allSourceSetsToProjects, projectDependency))
  }

  private def convertToPath(
      allSourceSetsToProjects: Map[SourceSet, Project],
      targetDir: File,
      projectDependencies: List[ProjectDependency],
      resolvedArtifact: ResolvedArtifact): Path = {
    projectDependencies
      .find(dep => isProjectDependency(dep, resolvedArtifact))
      .map(dep => dependencyToClassPath(allSourceSetsToProjects, targetDir, dep))
      .getOrElse(resolvedArtifact.getFile.toPath)
  }

  private def isProjectDependency(
      dependency: ProjectDependency,
      resolvedArtifact: ResolvedArtifact): Boolean = {
    dependency.getGroup == resolvedArtifact.getModuleVersion.getId.getGroup &&
    dependency.getName == resolvedArtifact.getModuleVersion.getId.getName &&
    dependency.getVersion == resolvedArtifact.getModuleVersion.getId.getVersion
  }

  private def isProjectDependency(
      projectDependencies: List[ProjectDependency],
      resolvedArtifact: ResolvedArtifact): Boolean = {
    projectDependencies.exists(dep => isProjectDependency(dep, resolvedArtifact))
  }

  private def artifactToConfigModule(
      artifact: ResolvedArtifact,
      project: Project): Config.Module = {

    val resolutionResult = project
      .getDependencies()
      .createArtifactResolutionQuery()
      .forComponents(artifact.getId.getComponentIdentifier)
      .withArtifacts(classOf[JvmLibrary], classOf[SourcesArtifact], classOf[JavadocArtifact])
      .execute()

    val name = artifact.getModuleVersion().getId().getName()
    val resolvedSourcesDependencies =
      resolutionResult
        .getResolvedComponents()
        .asScala
        .flatMap(_.getArtifacts(classOf[SourcesArtifact]).asScala)
        .collect {
          case artifact: ResolvedArtifactResult =>
            Config.Artifact(
              name = name,
              classifier = Option("sources"),
              checksum = None,
              path = artifact.getFile.toPath
            )
        }
        .toList

    Config.Module(
      organization = artifact.getModuleVersion().getId().getGroup(),
      name = artifact.getName(),
      version = artifact.getModuleVersion().getId().getVersion(),
      configurations = None,
      Config.Artifact(
        name = name,
        classifier = Option(artifact.getClassifier()),
        checksum = None,
        path = artifact.getFile().toPath()
      ) :: resolvedSourcesDependencies
    )
  }

  private def getScalaConfig(
      project: Project,
      sourceSet: SourceSet,
      artifacts: List[ResolvedArtifact]
  ): Try[Option[Config.Scala]] = {
    def isJavaOnly: Boolean = {
      val allSourceFiles = sourceSet.getAllSource.getFiles.asScala.toList
      !allSourceFiles.filter(f => f.exists && f.isFile).exists(_.getName.endsWith(".scala"))
    }

    // Finding the compiler group and version from the standard Scala library added as dependency
    artifacts.find(_.getName == parameters.stdLibName) match {
      case Some(stdLibArtifact) =>
        val scalaVersion = stdLibArtifact.getModuleVersion.getId.getVersion
        val scalaOrg = stdLibArtifact.getModuleVersion.getId.getGroup
        val scalaCompileTaskName = sourceSet.getCompileTaskName("scala")
        val scalaCompileTask = project.getTask[ScalaCompile](scalaCompileTaskName)

        if (scalaCompileTask != null) {
          val scalaJars = scalaCompileTask.getScalaClasspath.asScala.map(_.toPath).toList
          val opts = scalaCompileTask.getScalaCompileOptions
          val options = optionList(opts)
          val compilerName = parameters.compilerName

          // Use the compile setup and analysis out defaults, Gradle doesn't expose its customization
          Success(
            Some(
              Config.Scala(scalaOrg, compilerName, scalaVersion, options, scalaJars, None, None)
            )
          )
        } else {
          if (isJavaOnly) Success(None)
          else {
            // This is a heavy error on Gradle's side, but we will only report it in Scala projects
            Failure(
              new GradleException(s"$scalaCompileTaskName task is missing from ${project.getName}")
            )
          }
        }

      case None if isJavaOnly => Success(None)
      case None =>
        val target = s"project ${project.getName}/${sourceSet.getName}"
        val artifactNames =
          if (artifacts.isEmpty) ""
          else s" Found artifacts:\n${artifacts.map(_.getFile.toString).mkString("\n")}"
        Failure(
          new GradleException(
            s"Expected Scala standard library in classpath of $target that defines Scala sources.$artifactNames"
          )
        )
    }
  }

  private def getJavaConfig(project: Project, sourceSet: SourceSet): Option[Config.Java] = {
    val opts = getJavaCompileOptions(project, sourceSet)

    val specs = new DefaultJavaCompileSpec()
    specs.setCompileOptions(opts)

    val builder = new JavaCompilerArgumentsBuilder(specs)
      .includeMainOptions(true)
      .includeClasspath(false)
      .includeSourceFiles(false)
      .includeLauncherOptions(false)
    
    var args = builder.build().asScala.toList.filter(_.nonEmpty)

    if (!args.contains("-source")) {
      if (specs.getSourceCompatibility != null) {
        args = "-source" :: specs.getSourceCompatibility :: args
      } else {
        Option(DefaultInstalledJdk.current())
          .foreach(jvm => args = "-source" :: jvm.getJavaVersion.toString :: args)
      }
    }

    // if annotation processor is not configured to run we remove the source
    if (args.contains("-proc:none") && args.contains("-s")){
      args = args.takeWhile(_ != "-s") ++ args.dropWhile(_ != "-s").drop(2)
    } else if (args.contains("-s")){
      Files.createDirectories(Paths.get(args(args.indexOf("-s") + 1)))
    }

    if (!args.contains("-target")) {
      if (specs.getTargetCompatibility != null) {
        args = "-target" :: specs.getTargetCompatibility :: args
      } else {
        Option(DefaultInstalledJdk.current())
          .foreach(jvm => args = "-target" :: jvm.getJavaVersion.toString :: args)
      }
    }

    // Always return a java configuration (this cannot hurt us)
    Some(Config.Java(args))
  }

  private def ifEnabled[T](option: Boolean)(value: T): Option[T] =
    if (option) Some(value) else None

  private def optionList(options: ScalaCompileOptions): List[String] = {
    // based on ZincScalaCompilerArgumentsGenerator
    val baseOptions: Set[String] = Seq(
      ifEnabled(options.isDeprecation)("-deprecation"),
      ifEnabled(options.isUnchecked)("-unchecked"),
      ifEnabled(options.isOptimize)("-optimize"),
      ifEnabled(options.getDebugLevel == "verbose")("-verbose"),
      ifEnabled(options.getDebugLevel == "debug")("-Ydebug"),
      Option(options.getEncoding).map(encoding => s"-encoding$argumentSpaceSeparator$encoding"),
      Option(options.getDebugLevel).map(level => s"-g:$level")
    ).flatten.toSet

    val loggingPhases: Set[String] =
      Option(options.getLoggingPhases)
        .map(_.asScala.toSet)
        .getOrElse(Set.empty)
        .map(phase => s"-Ylog:$phase")

    val additionalOptions: Set[String] = {
      val opts = options.getAdditionalParameters
      if (opts == null) Set.empty
      else {
        // scalac options are passed back as Strings but under the hood can be GStringImpls which aren't Strings - so cope with that
        val optionList =
          opts.asScala.toList.asInstanceOf[List[Object]].filter(_ != null).map(_.toString)
        fuseOptionsWithArguments(optionList).toSet
      }
    }

    // Sort compiler flags to get a deterministic order when extracting the project
    splitFlags(baseOptions.union(loggingPhases).union(additionalOptions).toList.sorted)
  }

  private final val argumentSpaceSeparator = '\u0000'
  private final val argumentSpace = argumentSpaceSeparator.toString
  private def fuseOptionsWithArguments(scalacOptions: List[String]): List[String] = {
    scalacOptions match {
      case scalacOption :: rest =>
        val (args, remaining) = nextArgsAndRemaining(rest)
        val fused = (scalacOption :: args).mkString(argumentSpace)
        fused :: fuseOptionsWithArguments(remaining)
      case Nil => Nil
    }
  }

  private def nextArgsAndRemaining(scalacOptions: List[String]): (List[String], List[String]) = {
    scalacOptions match {
      case arg :: rest if !arg.startsWith("-") =>
        val (args, flags) = nextArgsAndRemaining(rest)
        (arg :: args, flags)
      // If next option starts with '-', then no scalac option is left to process
      case _ => (Nil, scalacOptions)
    }
  }

  private def splitFlags(values: List[String]): List[String] = {
    values.flatMap(value => value.split(argumentSpaceSeparator))
  }
}

object BloopConverter {
  case class SourceSetDep(bloopModuleName: String, classesDir: Path)
}
