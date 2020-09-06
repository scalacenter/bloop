package bloop.integrations.gradle.model

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

import bloop.config.{Config, Tag}
import bloop.config.Config.{CompileSetup, JavaThenScala, JvmConfig, Mixed, Platform}
import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.syntax._
import org.gradle.api.{Action, GradleException, Project}
import org.gradle.api.artifacts._
import org.gradle.api.artifacts.ArtifactView.ViewConfiguration
import org.gradle.api.artifacts.result.{ComponentArtifactsResult, ResolvedArtifactResult}
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.component.Artifact
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact
import org.gradle.api.internal.artifacts.publish.{ArchivePublishArtifact, DecoratingPublishArtifact}
import org.gradle.api.internal.file.copy.DefaultCopySpec
import org.gradle.api.internal.tasks.compile.{DefaultJavaCompileSpec, JavaCompilerArgumentsBuilder}
import org.gradle.api.plugins.{ApplicationPluginConvention, JavaPluginConvention}
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.{AbstractCopyTask, SourceSet}
import org.gradle.api.tasks.compile.{CompileOptions, JavaCompile}
import org.gradle.api.tasks.scala.{ScalaCompile, ScalaCompileOptions}
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.gradle.plugins.ide.internal.tooling.java.DefaultInstalledJdk

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import scala.io.Source

/**
 * Define the conversion from Gradle's project model to Bloop's project model.
 * @param parameters Plugin input parameters
 */
class BloopConverter(parameters: BloopParameters) {

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
   * @param project The Gradle project model
   * @param sourceSet The source set to convert
   * @return Bloop configuration
   */
  def toBloopConfig(
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
      // get Gradle output dirs
      val sourceSetSourceOutputDirs = sourceSet.getOutput.getClassesDirs.getFiles.asScala
      val sourceSetResourcesOutputDir = sourceSet.getOutput.getResourcesDir

      // get this project's classpath files
      // this needs to be done before the getArchiveSourceSetMap - then all is resolved correctly
      // otherwise running in parallel with multiproject can cause race conditions.
      val compileClassPathFiles = sourceSet.getCompileClasspath.asScala.toList
      val runtimeClassPathFiles = sourceSet.getRuntimeClasspath.asScala
        .filterNot(sourceSetSourceOutputDirs.contains)
        .filter(_ != sourceSetResourcesOutputDir)
        .toList

      // create map of every projects' sourcesets -> their Gradle projects
      val allSourceSetsToProjects = getSourceSetProjectMap(project.getRootProject)

      // create map of every projects' output jars -> source sets
      val allArchivesToSourceSets =
        getArchiveSourceSetMap(project.getRootProject, allSourceSetsToProjects.keySet)

      // create map of every projects' output dirs -> source sets
      val allOutputDirsToSourceSets = allSourceSetsToProjects.keySet
        .flatMap(
          ss =>
            ss.getOutput.getClassesDirs.getFiles.asScala.map(_ -> ss) +
              (ss.getOutput.getResourcesDir -> ss)
        )
        .toMap

      val projectName = getProjectName(project, sourceSet)

      // project dependencies
      // Bloop doesn't need transitive project dependencies but it's harder to exclude and won't hurt
      val projectDependencies = {
        val sourceSetDependencies =
          (compileClassPathFiles.flatMap(f => allArchivesToSourceSets.get(f)) ++
            compileClassPathFiles.flatMap(f => allOutputDirsToSourceSets.get(f)) ++
            runtimeClassPathFiles.flatMap(f => allArchivesToSourceSets.get(f)) ++
            runtimeClassPathFiles.flatMap(f => allOutputDirsToSourceSets.get(f))).distinct
        sourceSetDependencies.map(ss => getProjectName(allSourceSetsToProjects(ss), ss)).distinct
      }.filter(_ != projectName)

      // transform classpath
      // includes all transitive references.
      // this maintains order and includes project references that have been applied obliquely due to
      // various ways Gradle can reference test sourcesets
      val compileClasspathItems = getClassPathItems(
        compileClassPathFiles,
        allArchivesToSourceSets,
        allOutputDirsToSourceSets,
        allSourceSetsToProjects,
        targetDir
      )
      val runtimeClasspathItems = getClassPathItems(
        runtimeClassPathFiles,
        allArchivesToSourceSets,
        allOutputDirsToSourceSets,
        allSourceSetsToProjects,
        targetDir
      )

      // download Dotty artifacts
      val (dottyOrgName, dottyVersion, dottyArtifacts, dottyJars, dottyLibraryPaths) =
        parameters.dottyVersion
          .map(specifiedVersion => {
            val version = if (specifiedVersion.toUpperCase == "LATEST") {
              val source = Source.fromURL("https://dotty.epfl.ch/versions/latest-nightly-base")
              val majorVersionFromWebsite = source.getLines().toSeq.head
              source.close()
              majorVersionFromWebsite
            } else specifiedVersion
            val dottyOrgName = "ch.epfl.lamp"
            // handle major versions "0.24" and minor "0.24.0-bin-20200322-42932dc-NIGHTLY"
            val firstSeparator = version.indexOf('.')
            val lastSeparator = version.lastIndexOf('.')
            val dependencyName = if (firstSeparator == lastSeparator) {
              s"${dottyOrgName}:dotty_${version}:+"
            } else {
              val (major, minor) = version.splitAt(lastSeparator)
              s"${dottyOrgName}:dotty_${major}:${major}${minor}"
            }
            val dottyLibraryDep = project.getDependencies.create(dependencyName)
            val dottyConfiguration =
              project.getConfigurations.detachedConfiguration(dottyLibraryDep)
            val dottyCompilerClassPath = dottyConfiguration.resolve().asScala.map(_.toPath).toList
            val resolvedDottyArtifacts = getConfigurationArtifacts(dottyConfiguration)
            val dottyLibraryModule =
              resolvedDottyArtifacts
                .find(_.getId.getDisplayName.toUpperCase.contains("DOTTY-LIBRARY"))
            val dottyLibraryPaths = dottyLibraryModule.map(_.getFile.toPath).toList
            val exactVersion = dottyLibraryModule
              .map(_.getId)
              .collect {
                case mcai: ModuleComponentArtifactIdentifier =>
                  mcai.getComponentIdentifier.getVersion
              }
              .getOrElse(version)
            (
              Some(dottyOrgName),
              Some(exactVersion),
              resolvedDottyArtifacts,
              Some(dottyCompilerClassPath),
              dottyLibraryPaths
            )
          })
          .getOrElse((None, None, List.empty[ResolvedArtifactResult], None, List.empty))

      // get all configurations dependencies - these go into the resolutions as the user can create their own config dependencies (e.g. compiler plugin jar)
      // some configs aren't allowed to be resolved - hence the catch
      // this can bring too many artifacts into the resolution section (e.g. junit on main projects) but there's no way to know which artifact is required by which sourceset
      // filter out internal scala plugin configurations
      val additionalModules = project.getConfigurations.asScala
        .filter(_.isCanBeResolved)
        .filter(
          c =>
            !List(
              "incrementalScalaAnalysisElements",
              "incrementalScalaAnalysisFormain",
              "incrementalScalaAnalysisFortest",
              "zinc"
            ).contains(c.getName)
        )
        .flatMap(getConfigurationArtifacts)
        .filter(
          f =>
            !allArchivesToSourceSets.contains(f.getFile) &&
              !allOutputDirsToSourceSets.contains(f.getFile)
        )
        .map(artifactToConfigModule(_, project))
        .toList

      /* The classes directory is independent from Gradle's because Gradle has a different classes
       * directory for Scala and Java projects, whereas Bloop doesn't (it inherited this design from
       * sbt). Therefore, to avoid any compilation/test/run issue between Gradle and Bloop, we just
       * use our own classes 'bloop' directory in the ".bloop" directory. */
      val classesDir = getClassesDir(targetDir, project, sourceSet)
      val outDir = getOutDir(targetDir, project, sourceSet)

      // dotty files need to take precedence if they exist
      val compileClasspath: List[Path] =
        (dottyLibraryPaths ++ compileClasspathItems).distinct

      val runtimeClasspath: List[Path] =
        (dottyLibraryPaths ++ runtimeClasspathItems).distinct

      val modules = (additionalModules ++
        dottyArtifacts.map(artifactToConfigModule(_, project))).flatten.distinct

      // check paths of test tasks to check if this source set is to be tested
      val testTask = getTestTask(project, sourceSetSourceOutputDirs)
      val tags = if (testTask.nonEmpty) List(Tag.Test) else List(Tag.Library)

      // retrieve all artifacts to find what Scala library is being used
      val compileClassPathConfiguration =
        project.getConfiguration(sourceSet.getCompileClasspathConfigurationName)
      val compileArtifacts: List[ResolvedArtifactResult] = getConfigurationArtifacts(
        compileClassPathConfiguration
      )

      for {
        scalaConfig <- getScalaConfig(
          project,
          sourceSet,
          compileArtifacts,
          dottyOrgName,
          dottyVersion,
          dottyJars
        )
        resolution = Config.Resolution(modules)
        bloopProject = Config.Project(
          name = projectName,
          directory = project.getProjectDir.toPath,
          workspaceDir = Option(project.getRootProject.getProjectDir.toPath),
          sources = sources,
          sourcesGlobs = None,
          sourceRoots = None,
          dependencies = projectDependencies,
          classpath = compileClasspath,
          out = outDir,
          classesDir = classesDir,
          resources = if (resources.isEmpty) None else Some(resources),
          `scala` = scalaConfig,
          java = getJavaConfig(project, sourceSet),
          sbt = None,
          test = getTestConfig(testTask),
          platform = getPlatform(project, sourceSet, testTask, runtimeClasspath),
          resolution = Some(resolution),
          tags = Some(tags)
        )
      } yield Config.File(Config.File.LatestVersion, bloopProject)
    }
  }

  private def getConfigurationArtifacts(
      configuration: Configuration
  ): List[ResolvedArtifactResult] = {
    // get only jar artifacts
    val artifactType = Attribute.of("artifactType", classOf[String])
    val attributeType = "jar"
    configuration.getIncoming
      .artifactView(new Action[ViewConfiguration] {
        override def execute(viewConfig: ViewConfiguration): Unit = {
          viewConfig.setLenient(true)
          viewConfig.attributes(new Action[AttributeContainer] {
            override def execute(attributeContainer: AttributeContainer): Unit = {
              attributeContainer.attribute(artifactType, attributeType)
              ()
            }
          })
          ()
        }
      })
      .getArtifacts
      .asScala
      .toList
  }

  private def getClassPathItems(
      classPathFiles: List[File],
      allArchivesToSourceSets: Map[File, SourceSet],
      allOutputDirsToSourceSets: Map[File, SourceSet],
      allSourceSetsToProjects: Map[SourceSet, Project],
      targetDir: File
  ): List[Path] = {
    classPathFiles
      .map(f => {
        // change Gradle JAR references -> Bloop project classes dirs where possible.
        allArchivesToSourceSets
          .get(f)
          .map(ss => {
            val ssProject = allSourceSetsToProjects(ss)
            getClassesDir(targetDir, ssProject, ss)
          })
          .orElse(
            // change Gradle classes dirs -> Bloop project classes dirs where possible.
            allOutputDirsToSourceSets
              .get(f)
              .map(ss => {
                val ssProject = allSourceSetsToProjects(ss)
                getClassesDir(targetDir, ssProject, ss)
              })
          )
          .getOrElse(f.toPath)
      })
      .distinct
  }

  private def getSourceSetProjectMap(rootProject: Project): Map[SourceSet, Project] = {
    rootProject.getAllprojects.asScala
      .filter(isJavaProject)
      .flatMap(p => p.allSourceSets.map(_ -> p))
      .toMap
  }

  // version 4.9 of Gradle onwards can use lazy wrappers round delegates
  private def getDelegate(lpa: LazyPublishArtifact): Iterable[PublishArtifact] = {
    try {
      // private method
      val getDelegate = classOf[LazyPublishArtifact].getDeclaredMethod("getDelegate")
      getDelegate.setAccessible(true)
      Iterable(
        getDelegate
          .invoke(lpa)
          .asInstanceOf[PublishArtifact]
      )
    } catch {
      case _: NoSuchMethodException => Iterable.empty
    }
  }

  private def getTestTask(
      project: Project,
      sourceSetOutputFiles: collection.Set[File]
  ): Option[Test] = {
    // get the Test task associated with this sourceSet if there is one
    val testTasks = project.getTasks.asScala.collect {
      case test: Test => test
    }
    testTasks.find(testTask => {
      val testClassesDirs = testTask.getTestClassesDirs.asScala
      testClassesDirs.exists(sourceSetOutputFiles.contains)
    })
  }

  private def getArchiveTask(pa: PublishArtifact): Iterable[AbstractArchiveTask] = {
    pa match {
      case apa: ArchivePublishArtifact => Iterable(apa.getArchiveTask)
      case lpa: LazyPublishArtifact => getDelegate(lpa).flatMap(getArchiveTask)
      case dpa: DecoratingPublishArtifact => getArchiveTask(dpa.getPublishArtifact)
      case _ => Iterable.empty
    }
  }

  private def getArchiveSourceSetMap(
      rootProject: Project,
      sourceSets: Set[SourceSet]
  ): Map[File, SourceSet] = {
    rootProject.getAllprojects.asScala
      .filter(isJavaProject)
      .flatMap(_.getConfigurations.asScala.flatMap(c => {
        val archiveTasks = c.getAllArtifacts.asScala.flatMap(getArchiveTask)
        val possibleArchiveSourceSets =
          archiveTasks
            .flatMap(
              archiveTask =>
                getSourceSet(sourceSets, archiveTask)
                  .map(ss => archiveTask.getArchivePath -> ss)
            )
            .toMap
        possibleArchiveSourceSets
      }))
      .toMap
  }

  private def getJavaCompileTask(project: Project, sourceSet: SourceSet): JavaCompile = {
    val javaCompileTaskName = sourceSet.getCompileTaskName("java")
    project.getTask[JavaCompile](javaCompileTaskName)
  }

  private def getJavaCompileOptions(project: Project, sourceSet: SourceSet): CompileOptions = {
    getJavaCompileTask(project, sourceSet).getOptions
  }

  private def getPlatform(
      project: Project,
      sourceSet: SourceSet,
      testTask: Option[Test],
      runtimeClasspath: List[Path]
  ): Option[Platform] = {
    val forkOptions = getJavaCompileOptions(project, sourceSet).getForkOptions
    val projectJdkPath = Option(forkOptions.getJavaHome).map(_.toPath)

    val projectJvmOptions = testTask
      .map(task => {
        val testProperties =
          for ((name, value) <- task.getSystemProperties.asScala.toList)
            yield s"-D$name=$value"

        Option(task.getMinHeapSize).map(mem => s"-Xms$mem").toList ++
          Option(task.getMaxHeapSize).map(mem => s"-Xmx$mem").toList ++
          task.getJvmArgs.asScala.toList ++
          testProperties
      })
      .getOrElse(
        Option(forkOptions.getMemoryInitialSize).map(mem => s"-Xms$mem").toList ++
          Option(forkOptions.getMemoryMaximumSize).map(mem => s"-Xmx$mem").toList ++
          forkOptions.getJvmArgs.asScala.toList
      )

    val mainClass =
      if (testTask.isEmpty)
        Option(project.getConvention.findPlugin(classOf[ApplicationPluginConvention]))
          .map(_.getMainClassName)
      else
        None

    val jdkPath = projectJdkPath.orElse({
      val currentJDK = DefaultInstalledJdk.current()
      Option(currentJDK).map(_.getJavaHome.toPath)
    })
    Some(
      Platform.Jvm(JvmConfig(jdkPath, projectJvmOptions), mainClass, Some(runtimeClasspath), None)
    )
  }

  private def getTestConfig(testTask: Option[Test]): Option[Config.Test] = {
    // TODO: make this configurable?
    testTask.map(_ => Config.Test.defaultConfiguration)
  }

  def getProjectName(project: Project, sourceSet: SourceSet): String = {
    val projectsWithName = project.getRootProject
      .getAllprojects()
      .asScala
      .filter(p => p.getName == project.getName)
    val rawProjectName = if (projectsWithName.size == 1) project.getName else project.getPath
    val sanitizedProjectName = rawProjectName.zipWithIndex
      .map {
        case (c, i) =>
          if (i == 0 && c == ':') {
            None
          } else if (c == ':') {
            Some('-')
          } else {
            Some(c)
          }
      }
      .flatten
      .mkString
    if (sourceSet.getName == SourceSet.MAIN_SOURCE_SET_NAME) {
      sanitizedProjectName
    } else {
      s"${sanitizedProjectName}-${sourceSet.getName}"
    }
  }

  // find the source of the data going into an archive
  private def getSourceSet(
      allSourceSets: Set[SourceSet],
      abstractCopyTask: AbstractCopyTask
  ): Option[SourceSet] = {
    try {
      // protected method
      val getMainSpec = classOf[AbstractCopyTask].getDeclaredMethod("getMainSpec")
      getMainSpec.setAccessible(true)
      val mainSpec = getMainSpec.invoke(abstractCopyTask)
      if (mainSpec == null)
        None
      else {
        mainSpec
          .asInstanceOf[DefaultCopySpec]
          .getSourcePaths
          .asScala
          .flatMap(sourcePath => allSourceSets.find(_.getOutput == sourcePath))
          .headOption
      }
    } catch {
      case _: NoSuchMethodException => None;
    }
  }

  private def isJavaProject(project: Project): Boolean = {
    project.getConvention.findPlugin(classOf[JavaPluginConvention]) != null
  }

  private def getOutDir(targetDir: File, projectName: String): Path =
    (targetDir / projectName / "build").toPath

  private def getOutDir(targetDir: File, project: Project, sourceSet: SourceSet): Path =
    getOutDir(targetDir, getProjectName(project, sourceSet))

  private def getClassesDir(targetDir: File, projectName: String): Path =
    (targetDir / projectName / "build" / "classes").toPath

  def getClassesDir(targetDir: File, project: Project, sourceSet: SourceSet): Path =
    getClassesDir(targetDir, getProjectName(project, sourceSet))

  private def getSources(sourceSet: SourceSet): List[Path] =
    sourceSet.getAllSource.getSrcDirs.asScala.map(_.toPath).toList

  private def getResources(sourceSet: SourceSet): List[Path] =
    sourceSet.getResources.getSrcDirs.asScala.map(_.toPath).toList

  private def createArtifact(
      resolvedArtifactResult: ResolvedArtifactResult,
      name: String,
      classifier: String
  ): Config.Artifact = {
    Config.Artifact(
      name = name,
      classifier = Option(classifier),
      checksum = None,
      path = resolvedArtifactResult.getFile.toPath
    )
  }

  private def getArtifacts(
      resolvedArtifacts: collection.Set[ComponentArtifactsResult],
      name: String,
      artifactClass: Class[_ <: Artifact],
      classifier: String
  ): collection.Set[Config.Artifact] = {
    resolvedArtifacts
      .flatMap(
        _.getArtifacts(artifactClass).asScala
          .collect {
            case resolvedArtifact: ResolvedArtifactResult =>
              createArtifact(resolvedArtifact, name, classifier)
          }
      )
  }

  private def artifactToConfigModule(
      artifactResult: ResolvedArtifactResult,
      project: Project
  ): Option[Config.Module] = {
    artifactResult.getId match {
      case mcai: ModuleComponentArtifactIdentifier =>
        val javadocArtifact =
          if (parameters.includeJavadoc) Seq(classOf[JavadocArtifact]) else Seq.empty
        val sourcesArtifact =
          if (parameters.includeSources) Seq(classOf[SourcesArtifact]) else Seq.empty

        val resolutionResult = project.getDependencies
          .createArtifactResolutionQuery()
          .forComponents(mcai.getComponentIdentifier)
          .withArtifacts(classOf[JvmLibrary], javadocArtifact ++ sourcesArtifact: _*)
          .execute()

        val name = mcai.getComponentIdentifier.getModule
        val resolvedArtifacts = resolutionResult.getResolvedComponents.asScala
        val configArtifacts =
          getArtifacts(resolvedArtifacts, name, classOf[SourcesArtifact], "sources") ++
            getArtifacts(resolvedArtifacts, name, classOf[JavadocArtifact], "javadoc") +
            Config.Artifact(
              name = name,
              classifier = None,
              checksum = None,
              path = artifactResult.getFile.toPath
            )

        Some(
          Config.Module(
            organization = mcai.getComponentIdentifier.getGroup,
            name = name,
            version = mcai.getComponentIdentifier.getVersion,
            configurations = None,
            configArtifacts.toList
          )
        )
      case _ => None
    }
  }

  private def getScalaConfig(
      project: Project,
      sourceSet: SourceSet,
      artifacts: List[ResolvedArtifactResult],
      dottyOrgName: Option[String],
      dottyVersion: Option[String],
      dottyJars: Option[List[Path]]
  ): Try[Option[Config.Scala]] = {
    def isJavaOnly: Boolean = {
      val allSourceFiles = sourceSet.getAllSource.getFiles.asScala.toList
      !allSourceFiles.filter(f => f.exists && f.isFile).exists(_.getName.endsWith(".scala"))
    }

    // Finding the compiler group and version from the standard Scala library added as dependency
    artifacts
      .map(_.getId)
      .collect({ case mcai: ModuleComponentArtifactIdentifier => mcai })
      .find(_.getComponentIdentifier.getModule == parameters.stdLibName) match {
      case Some(stdLibArtifact) =>
        val scalaCompileTaskName = sourceSet.getCompileTaskName("scala")
        val scalaCompileTask = project.getTask[ScalaCompile](scalaCompileTaskName)

        if (scalaCompileTask != null) {
          val scalaVersion =
            dottyVersion.getOrElse(stdLibArtifact.getComponentIdentifier.getVersion)
          val scalaOrg = dottyOrgName.getOrElse(stdLibArtifact.getComponentIdentifier.getGroup)
          val scalaJars =
            dottyJars.getOrElse(scalaCompileTask.getScalaClasspath.asScala.map(_.toPath).toList)
          val opts = scalaCompileTask.getScalaCompileOptions
          val options = optionList(opts) ++ getPluginsAsOptions(scalaCompileTask)
          val compilerName = parameters.compilerName
          val compileOrder =
            if (!sourceSet.getJava.getSourceDirectories.isEmpty) JavaThenScala
            else Mixed
          val setup = CompileSetup.empty.copy(order = compileOrder)

          // Use the compile setup and analysis out defaults, Gradle doesn't expose its customization
          Success(
            Some(
              Config
                .Scala(scalaOrg, compilerName, scalaVersion, options, scalaJars, None, Some(setup))
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
          else
            s" Found artifacts:\n${artifacts.map(a => s"${a.getId.getDisplayName} ${a.getFile}").mkString("\n")}"
        Failure(
          new GradleException(
            s"Expected ${parameters.stdLibName} library in classpath of $target that defines Scala sources.$artifactNames"
          )
        )
    }
  }

  private def getPluginsAsOptions(scalaCompile: ScalaCompile): List[String] = {
    // Gradle 6.4 has scalaCompilerPlugins option
    try {
      val getScalaCompilerPlugins =
        classOf[ScalaCompile].getDeclaredMethod("getScalaCompilerPlugins")
      getScalaCompilerPlugins
        .invoke(scalaCompile)
        .asInstanceOf[FileCollection]
        .asScala
        .map(f => s"-Xplugin:$f")
        .toList
    } catch {
      case _: NoSuchMethodException => List.empty
    }
  }

  private def getJavaConfig(project: Project, sourceSet: SourceSet): Option[Config.Java] = {
    val compileTask = getJavaCompileTask(project, sourceSet)
    val opts = compileTask.getOptions

    val specs = new DefaultJavaCompileSpec()
    specs.setCompileOptions(opts)
    specs.setSourceCompatibility(compileTask.getSourceCompatibility)
    specs.setTargetCompatibility(compileTask.getTargetCompatibility)

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
    if (args.contains("-proc:none") && args.contains("-s")) {
      args = args.takeWhile(_ != "-s") ++ args.dropWhile(_ != "-s").drop(2)
    } else if (args.contains("-s")) {
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
