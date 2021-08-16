package bloop.integrations.gradle.model

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

import bloop.config.{Config, Tag}
import bloop.config.Config.{CompileSetup, JavaThenScala, JvmConfig, Mixed, Platform}
import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.syntax._
import bloop.integrations.gradle.SemVer
import bloop.integrations.gradle.tasks.PluginUtils
import org.gradle.api.{Action, GradleException, Project}
import org.gradle.api.artifacts.{Configuration, PublishArtifact}
import org.gradle.api.artifacts.ArtifactView.ViewConfiguration
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.{ComponentArtifactsResult, ResolvedArtifactResult}
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.component.Artifact
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact
import org.gradle.api.internal.artifacts.publish.{ArchivePublishArtifact, DecoratingPublishArtifact}
import org.gradle.api.internal.file.copy.DefaultCopySpec
import org.gradle.api.internal.file.CompositeFileCollection
import org.gradle.api.internal.tasks.compile.{DefaultJavaCompileSpec, JavaCompilerArgumentsBuilder}
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
import scala.annotation.tailrec

import com.android.builder.model.SourceProvider
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.tasks.PackageAndroidArtifact
import com.android.build.gradle.tasks.PackageApplication
import com.android.build.gradle.internal.tasks.AndroidVariantTask
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.BaseVariant
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.file.RegularFile
import org.gradle.api.file.Directory

/**
 * Define the conversion from Gradle's project model to Bloop's project model.
 * @param parameters Plugin input parameters
 */
class BloopConverter(parameters: BloopParameters, info: String => Unit) {

  def toBloopConfig(
      projectName: String,
      project: Project,
      variant: BaseVariant,
      sourceProviders: List[SourceProvider],
      targetDir: File
  ): Try[Config.File] = {

    //  build config is a generated source - needs to be added manually
    val buildConfigLocation = Option(variant.getGenerateBuildConfigProvider.getOrNull())
      .map(f => {
        // Using reflection because in Android plugin v4.0 this is a File. In v4.1 this is a DirectoryProvider.
        val getSourceOutputDirMethod = f.getClass.getMethod("getSourceOutputDir")
        getSourceOutputDirMethod.invoke(f) match {
          case f: File => f.toPath
          case f: DirectoryProperty => f.getAsFile.get.toPath
          case default =>
            throw new IllegalStateException(s"Build Config Provider API changed $default")
        }
      })
      .toList

    val resources = sourceProviders.flatMap(_.getResourcesDirectories.asScala.map(_.toPath).toList)
    val pureSources = sourceProviders.flatMap(_.getJavaDirectories.asScala.map(_.toPath).toList)

    val isTestSourceSet = variant.isInstanceOf[TestVariant]

    // Gradle always creates a main and test source set regardless of whether they are needed.
    // ignore test sourceset if there are no sources or resources
    if (
      isTestSourceSet &&
      !pureSources.exists(_.toFile.exists()) &&
      !resources.exists(_.toFile.exists())
    ) {
      Failure(new GradleException("Test project has no source so ignore it"))
    } else {

      val sources = buildConfigLocation ::: pureSources

      // create map of every projects' sourcesets -> their Gradle project/variants
      val allSourceSetsToProjectVariants = getAndroidSourceSetProjectVariantMap(
        project.getRootProject
      )
      // create map of every non-Android projects' sourcesets -> their Gradle projects
      val allSourceSetsToProjects = getSourceSetProjectMap(project.getRootProject)

      // create map of every projects' output jars and classes dir -> source sets
      val allOutputsToSourceSets = getAndroidOutputsSourceSetMap(allSourceSetsToProjectVariants)
      // create map of every non-Android projects' output jars -> source sets
      val allArchivesToSourceSets =
        getArchiveSourceSetMap(project.getRootProject, allSourceSetsToProjects.keySet)
      // create map of every non-Android projects' output dirs -> source sets
      val allOutputDirsToSourceSets = getOutputDirsToSourceSets(allSourceSetsToProjects)

      // doesn't appear on classpath but is needed on classpath
      val androidJarLocation = project.androidJar.toList

      // get this project's classpath files
      val compileClassPathFiles = androidJarLocation ::: getAndroidJavaCompile(variant)
        .map(javaCompile => javaCompile.getClasspath().asScala.toList)
        .getOrElse(List.empty)
      val runtimeClassPathFiles = compileClassPathFiles

      // project dependencies
      // Bloop doesn't need transitive project dependencies but it's harder to exclude and won't hurt
      val androidProjectDependencies = getAndroidProjectDependencies(
        compileClassPathFiles,
        runtimeClassPathFiles,
        allOutputsToSourceSets,
        allSourceSetsToProjectVariants,
        projectName
      )

      val nonAndroidProjectDependencies = getProjectDependencies(
        compileClassPathFiles,
        runtimeClassPathFiles,
        allArchivesToSourceSets,
        allOutputDirsToSourceSets,
        allSourceSetsToProjects,
        projectName
      )

      val projectDependencies =
        (androidProjectDependencies ::: nonAndroidProjectDependencies).distinct

      // transform classpath
      // includes all transitive references.
      // this maintains order and includes project references that have been applied obliquely due to
      // various ways Gradle can reference test sourcesets
      val partiallyTranslatedCompileClasspathItems = getAndroidClassPathItems(
        compileClassPathFiles,
        allOutputsToSourceSets,
        allSourceSetsToProjectVariants,
        targetDir
      ).map(_.toFile)
      val compileClasspathItems = getClassPathItems(
        partiallyTranslatedCompileClasspathItems,
        allArchivesToSourceSets,
        allOutputDirsToSourceSets,
        allSourceSetsToProjects,
        targetDir
      )

      // get all configurations dependencies - these go into the resolutions as the user can create their own config dependencies (e.g. compiler plugin jar)
      // some configs aren't allowed to be resolved - hence the catch
      // this can bring too many artifacts into the resolution section (e.g. junit on main projects) but there's no way to know which artifact is required by which sourceset
      // filter out internal scala plugin configurations
      val additionalModules = project.getConfigurations.asScala
        .filter(_.isCanBeResolved)
        .flatMap(getConfigurationArtifacts)
        .filterNot(f => allOutputsToSourceSets.contains(f.getFile))
        .map(artifactToConfigModule(_, project))
        .toList

      /* The classes directory is independent from Gradle's because Gradle has a different classes
       * directory for Scala and Java projects, whereas Bloop doesn't (it inherited this design from
       * sbt). Therefore, to avoid any compilation/test/run issue between Gradle and Bloop, we just
       * use our own classes 'bloop' directory in the ".bloop" directory. */
      val classesDir = getClassesDir(targetDir, projectName)
      val outDir = getOutDir(targetDir, projectName)

      val modules = additionalModules.flatten.distinct

      val (tags, testConfig) =
        if (isTestSourceSet)
          (List(Tag.Test), Some(Config.Test.defaultConfiguration))
        else (List(Tag.Library), None)

      val bloopProject = Config.Project(
        name = projectName,
        directory = project.getProjectDir.toPath,
        workspaceDir = Option(project.getRootProject.getProjectDir.toPath),
        sources = sources,
        sourcesGlobs = None,
        sourceRoots = None,
        dependencies = projectDependencies,
        classpath = compileClasspathItems,
        out = outDir,
        classesDir = classesDir,
        resources = if (resources.isEmpty) None else Some(resources),
        `scala` = None,
        java = getAndroidJavaConfig(project, variant),
        sbt = None,
        test = testConfig,
        platform = None,
        resolution = if (modules.isEmpty) None else Some(Config.Resolution(modules)),
        tags = if (tags.isEmpty) None else Some(tags)
      )
      Success(Config.File(Config.File.LatestVersion, bloopProject))
    }
  }

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
   * @param projectName unique project name
   * @param project The Gradle project model
   * @param sourceSet The source set to convert
   * @return Bloop configuration
   */
  def toBloopConfig(
      projectName: String,
      project: Project,
      sourceSet: SourceSet,
      targetDir: File
  ): Try[Config.File] = {

    val resources = getResources(sourceSet)
    val sources = getSources(sourceSet)

    val isTestSourceSet = sourceSet.getName == SourceSet.TEST_SOURCE_SET_NAME

    // Gradle always creates a main and test source set regardless of whether they are needed.
    // ignore test sourceset if there are no sources or resources
    if (
      isTestSourceSet &&
      !sources.exists(_.toFile.exists()) &&
      !resources.exists(_.toFile.exists())
    ) {
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
      val allOutputDirsToSourceSets = getOutputDirsToSourceSets(allSourceSetsToProjects)

      val projectName = getProjectName(project, sourceSet)

      // project dependencies
      // Bloop doesn't need transitive project dependencies but it's harder to exclude and won't hurt
      val projectDependencies = getProjectDependencies(
        compileClassPathFiles,
        runtimeClassPathFiles,
        allArchivesToSourceSets,
        allOutputDirsToSourceSets,
        allSourceSetsToProjects,
        projectName
      )

      // transform classpath
      // includes all transitive references.
      // this maintains order and includes project references that have been applied obliquely due to
      // various ways Gradle can reference test sourcesets
      val compileClasspath = getClassPathItems(
        compileClassPathFiles,
        allArchivesToSourceSets,
        allOutputDirsToSourceSets,
        allSourceSetsToProjects,
        targetDir
      )
      val runtimeClasspath = getClassPathItems(
        runtimeClassPathFiles,
        allArchivesToSourceSets,
        allOutputDirsToSourceSets,
        allSourceSetsToProjects,
        targetDir
      )

      // get all configurations dependencies - these go into the resolutions as the user can create their own config dependencies (e.g. compiler plugin jar)
      // some configs aren't allowed to be resolved - hence the catch
      // this can bring too many artifacts into the resolution section (e.g. junit on main projects) but there's no way to know which artifact is required by which sourceset
      // filter out internal scala plugin configurations
      val modules = project.getConfigurations.asScala
        .filter(_.isCanBeResolved)
        .filter(c =>
          !List(
            "incrementalScalaAnalysisElements",
            "incrementalScalaAnalysisFormain",
            "incrementalScalaAnalysisFortest",
            "zinc"
          ).contains(c.getName)
        )
        .flatMap(getConfigurationArtifacts)
        .filter(f =>
          !allArchivesToSourceSets.contains(f.getFile) &&
            !allOutputDirsToSourceSets.contains(f.getFile)
        )
        .map(artifactToConfigModule(_, project))
        .toList
        .flatten
        .distinct

      /* The classes directory is independent from Gradle's because Gradle has a different classes
       * directory for Scala and Java projects, whereas Bloop doesn't (it inherited this design from
       * sbt). Therefore, to avoid any compilation/test/run issue between Gradle and Bloop, we just
       * use our own classes 'bloop' directory in the ".bloop" directory. */
      val classesDir = getClassesDir(targetDir, projectName)
      val outDir = getOutDir(targetDir, projectName)

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
          compileArtifacts
        )

        bloopProject = Config.Project(
          name = projectName,
          directory = project.getProjectDir.toPath,
          workspaceDir = Option(project.getRootProject.workspacePath),
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
          resolution = if (modules.isEmpty) None else Some(Config.Resolution(modules)),
          tags = if (tags.isEmpty) None else Some(tags)
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
      .flatMap(f => {
        // change Gradle JAR references -> Bloop project classes dirs where possible.
        allArchivesToSourceSets
          .get(f)
          .map(ss => {
            val ssProject = allSourceSetsToProjects(ss)
            getClassesDir(targetDir, ssProject, ss) :: getResources(ss)
          })
          .orElse(
            // change Gradle classes dirs -> Bloop project classes dirs where possible.
            allOutputDirsToSourceSets
              .get(f)
              .map(ss => {
                val ssProject = allSourceSetsToProjects(ss)
                getClassesDir(targetDir, ssProject, ss) :: getResources(ss)
              })
          )
          .getOrElse(List(f.toPath))
      })
      .distinct
  }

  private def getSourceSetProjectMap(rootProject: Project): Map[SourceSet, Project] = {
    getAllBloopCapableProjects(rootProject)
      .flatMap(p => p.allSourceSets.map(_ -> p))
      .toMap
  }

  private def getOutputDirsToSourceSets(
      allSourceSetsToProjects: Map[SourceSet, Project]
  ): Map[File, SourceSet] = {
    allSourceSetsToProjects.keySet
      .flatMap(ss =>
        ss.getOutput.getClassesDirs.getFiles.asScala.map(_ -> ss) +
          (ss.getOutput.getResourcesDir -> ss)
      )
      .toMap
  }

  private def getProjectDependencies(
      compileClassPathFiles: List[File],
      runtimeClassPathFiles: List[File],
      allArchivesToSourceSets: Map[File, SourceSet],
      allOutputDirsToSourceSets: Map[File, SourceSet],
      allSourceSetsToProjects: Map[SourceSet, Project],
      projectName: String
  ): List[String] = {
    val sourceSetDependencies =
      (compileClassPathFiles.flatMap(f => allArchivesToSourceSets.get(f)) ++
        compileClassPathFiles.flatMap(f => allOutputDirsToSourceSets.get(f)) ++
        runtimeClassPathFiles.flatMap(f => allArchivesToSourceSets.get(f)) ++
        runtimeClassPathFiles.flatMap(f => allOutputDirsToSourceSets.get(f))).distinct
    sourceSetDependencies
      .map(ss => getProjectName(allSourceSetsToProjects(ss), ss))
      .distinct
      .filter(_ != projectName)
  }

  private def getAndroidProjectDependencies(
      compileClassPathFiles: List[File],
      runtimeClassPathFiles: List[File],
      allOutputsToSourceSets: Map[File, SourceProvider],
      allSourceSetsToProjectVariants: Map[SourceProvider, (Project, BaseVariant)],
      projectName: String
  ): List[String] = {
    val sourceSetDependencies =
      (compileClassPathFiles.flatMap(f => allOutputsToSourceSets.get(f)) ++
        runtimeClassPathFiles.flatMap(f => allOutputsToSourceSets.get(f))).distinct
    sourceSetDependencies
      .map(ss => {
        val (project, variant) = allSourceSetsToProjectVariants(ss)
        getAndroidProjectName(project, variant)
      })
      .distinct
      .filter(_ != projectName)
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
      .flatMap(_.getConfigurations.asScala.flatMap(c => {
        val archiveTasks = c.getAllArtifacts.asScala.flatMap(getArchiveTask)
        val possibleArchiveSourceSets =
          archiveTasks
            .flatMap(archiveTask =>
              getSourceSet(sourceSets, archiveTask)
                .map(ss => archiveTask.getArchivePath -> ss)
            )
            .toMap
        possibleArchiveSourceSets
      }))
      .toMap
  }

  private def getAndroidClassPathItems(
      classPathFiles: List[File],
      allOutputsToSourceSets: Map[File, SourceProvider],
      allSourceSetsToProjectVariants: Map[SourceProvider, (Project, BaseVariant)],
      targetDir: File
  ): List[Path] = {
    classPathFiles
      .map(f => {
        // change Gradle JAR references -> Bloop project classes dirs where possible.
        allOutputsToSourceSets
          .get(f)
          .map(ss => {
            val (project, variant) = allSourceSetsToProjectVariants(ss)
            getAndroidClassesDir(targetDir, project, variant)
          })
          .getOrElse(f.toPath)
      })
      .distinct
  }

  private def getAndroidSourceSetProjectVariantMap(
      rootProject: Project
  ): Map[SourceProvider, (Project, BaseVariant)] = {
    (for {
      project <- rootProject.getAllprojects.asScala
      nonTestVariant <- project.androidVariants
      variant <- nonTestVariant :: Option(nonTestVariant.getTestVariant).toList
      sourceSet <- variant.getSourceSets.asScala
    } yield (sourceSet -> ((project, variant)))).toMap
  }

  private def getAndroidOutputsSourceSetMap(
      allSourceSetsToProjectVariants: Map[SourceProvider, (Project, BaseVariant)]
  ): Map[File, SourceProvider] = {
    val apiVersion =
      SemVer.Version.fromString(com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION)
    val bundleAllClassesClass = Class
      .forName("com.android.build.gradle.internal.feature.BundleAllClasses")
      .asInstanceOf[Class[AndroidVariantTask]]
    allSourceSetsToProjectVariants.flatMap {
      case (sourceProvider, (project, variant)) =>
        val bundleAppTasks = project.getTasks
          .withType(bundleAllClassesClass)
          .asScala
          .filter(_.getVariantName == variant.getName)

        val apps = bundleAppTasks
          .flatMap(task => {
            // 3.4.0   BundleAllClasses  getJavacClasses  BuildableArtifact (doesn't exist anymore - ignore it)
            // 3.5.0   BundleAllClasses  getJavacClasses  Provider<Directory>
            // 3.6.0   BundleAllClasses  getJavacClasses  DirectoryProperty (subclass of Provider<Directory>)
            // 4.2.0   BundleAllClasses  getInputDirs     ConfigurableFileCollection
            val getJavacClassesMethodName =
              if (apiVersion < SemVer.Version.fromString("4.2.0")) "getJavacClasses"
              else "getInputDirs"
            val getJavacClassesMethod = task.getClass.getMethod(getJavacClassesMethodName)
            val classes = getJavacClassesMethod.invoke(task) match {
              case provider: Provider[_] =>
                Option(provider.getOrNull)
                  .map(_ match {
                    case directory: Directory => directory.getAsFile
                  })
                  .toSet
              case fileCollection: ConfigurableFileCollection => fileCollection.getFiles.asScala
              case _ => Set.empty[File]
            }

            // 3.4.0   BundleAllClasses  getOutputJar  File
            // 3.6.0   BundleAllClasses  getOutputJar  RegularFileProperty
            val getOutputJarMethod = task.getClass.getMethod("getOutputJar")
            val outputJar = getOutputJarMethod.invoke(task) match {
              case f: File => Set(f)
              case f: RegularFileProperty => Option(f.getOrNull).map(_.getAsFile).toSet
            }
            classes ++ outputJar
          })

        // uses reflection to support multiple API versions...
        val libs = {
          // 3.4.0   BundleLibraryClasses     getOutput     Provider<RegularFile>
          // 3.6.0   BundleLibraryClasses     getOutput     RegularFileProperty
          // 4.0.0   BundleLibraryClasses     getJarOutput  RegularFileProperty
          // 4.1.0   BundleLibraryClassesJar  getOutput     RegularFileProperty
          val bundleLibraryClassName =
            if (apiVersion < SemVer.Version.fromString("4.1.0"))
              "com.android.build.gradle.internal.tasks.BundleLibraryClasses"
            else
              "com.android.build.gradle.internal.tasks.BundleLibraryClassesJar"
          val bundleLibraryClass = Class.forName(bundleLibraryClassName).asInstanceOf[Class[Task]]
          val bundleLibraryTasks = project.getTasks.withType(bundleLibraryClass).asScala
          val getVariantNameMethod = bundleLibraryClass.getMethod("getVariantName")
          val getOutputMethodName =
            if (apiVersion < SemVer.Version.fromString("4.1.0")) "getJarOutput"
            else if (apiVersion < SemVer.Version.fromString("4.0.0")) "getOutput"
            else "getOutput"
          val getOutputMethod = bundleLibraryClass.getMethod(getOutputMethodName)
          val getClassesMethod = bundleLibraryClass.getMethod("getClasses")

          bundleLibraryTasks
            .filter(getVariantNameMethod.invoke(_) == variant.getName)
            .flatMap(task => {
              val classes =
                try {
                  getClassesMethod.invoke(task) match {
                    case f: ConfigurableFileCollection => f.getFiles.asScala
                    case _: Exception => Set.empty[File]
                  }
                } catch {
                  case _: Exception => Set.empty[File]
                }
              val jarOutput = getOutputMethod.invoke(task) match {
                case f: RegularFileProperty => f.getOrNull
                case _: Exception => null
              }
              classes ++ Option(jarOutput).map(_.getAsFile)
            })
        }
        // R.jar is needed on classpaths - don't substitute it for a project's classes dir
        val outputDirsAndJars = (libs ++ apps).filterNot(_.getName().equalsIgnoreCase("R.jar"))
        outputDirsAndJars.map(_ -> sourceProvider)
    }.toMap
  }

  private def getJavaCompileTask(project: Project, sourceSet: SourceSet): JavaCompile = {
    val javaCompileTaskName = sourceSet.getCompileTaskName("java")
    project.getTask[JavaCompile](javaCompileTaskName)
  }

  private def getJavaCompileOptions(project: Project, sourceSet: SourceSet): CompileOptions = {
    getJavaCompileTask(project, sourceSet).getOptions
  }

  private def getAndroidJavaCompile(variant: BaseVariant): Option[JavaCompile] = {
    Option(variant.getJavaCompileProvider().getOrNull)
  }

  private def getAndroidJavaConfig(project: Project, variant: BaseVariant): Option[Config.Java] = {
    getAndroidJavaCompile(variant).flatMap(javaCompile => {
      val options = javaCompile.getOptions
      // bug in DefaultJavaCompileSpec handling Android bootstrapClasspath causes crash so set to null
      options.setBootstrapClasspath(null)
      getJavaConfig(project, javaCompile, options)
    })
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
      .orElse(
        project.javaApplicationExt
          .flatMap(f => Option(f.getApplicationDefaultJvmArgs).map(_.asScala.toList))
      )
      .getOrElse(
        Option(forkOptions.getMemoryInitialSize).map(mem => s"-Xms$mem").toList ++
          Option(forkOptions.getMemoryMaximumSize).map(mem => s"-Xmx$mem").toList ++
          forkOptions.getJvmArgs.asScala.toList
      )

    val mainClass =
      if (testTask.isEmpty)
        project.javaApplicationExt.flatMap(f => Option(f.getMainClassName))
      else
        None

    val jdkPath = projectJdkPath.orElse({
      val currentJDK = DefaultInstalledJdk.current()
      Option(currentJDK).map(_.getJavaHome.toPath)
    })
    Some(
      Platform
        .Jvm(JvmConfig(jdkPath, projectJvmOptions), mainClass, None, Some(runtimeClasspath), None)
    )
  }

  private def getTestConfig(testTask: Option[Test]): Option[Config.Test] = {
    // TODO: make this configurable?
    testTask.map(_ => Config.Test.defaultConfiguration)
  }

  // create a minimal unique project name
  // e.g. if "Foo" exists twice with paths...
  // "a:b:c:Foo" and "a:b:d:Foo"
  // then names should be
  // "c-Foo" and "d-Foo"
  // not
  // "a-b-c-Foo" and "a-b-d-Foo"
  private def createUniqueProjectName(project: Project, suffix: Option[String]): String = {

    def getFQName(project: Project): String = {
      // gradle getPath is inconsistent - it returns ":" for rootProject and doesn't preface non-root project with rootProject.name
      if (project == project.getRootProject())
        project.getName
      else
        s"${project.getRootProject().getName}${project.getPath()}"
    }

    def getReversedFQNameParts(project: Project): Array[String] = {
      getFQName(project).split(':').reverse
    }

    @tailrec
    def getUniqueSections(
        idx: Int,
        fqNameParts: Array[String],
        fqNamesParts: List[Array[String]]
    ): Array[String] = {
      if (idx >= fqNameParts.length)
        fqNameParts
      else {
        val partialName = fqNameParts.take(idx)
        val partialNames = fqNamesParts.map(_.take(idx))
        if (!partialNames.exists(_.sameElements(partialName)))
          partialName
        else
          getUniqueSections(idx + 1, fqNameParts, fqNamesParts)
      }
    }

    // Need to namespace only those projects that can run bloop. Others would not cause collision.
    val projectsWithSameName =
      getAllBloopCapableProjects(project.getRootProject())
        .filter(_.getName == project.getName)

    val uniqueProjectName =
      if (projectsWithSameName.size == 1) project.getName
      else {
        val fqNameParts = getReversedFQNameParts(project)
        val fqNamesParts =
          projectsWithSameName.map(getReversedFQNameParts).filter(!_.sameElements(fqNameParts))
        getUniqueSections(1, fqNameParts, fqNamesParts).reverse.mkString("-")
      }
    suffix.map(s => s"${uniqueProjectName}-$s").getOrElse(uniqueProjectName)
  }

  def getProjectName(project: Project, sourceSet: SourceSet): String = {
    val suffix =
      if (sourceSet.getName == SourceSet.MAIN_SOURCE_SET_NAME) None else Some(sourceSet.getName)
    createUniqueProjectName(project, suffix)
  }

  def getAndroidProjectName(
      project: Project,
      variant: BaseVariant
  ): String = {
    createUniqueProjectName(project, Some(variant.getBaseName))
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

  private def getOutDir(targetDir: File, projectName: String): Path =
    (targetDir / projectName / "build").toPath

  private def getOutDir(targetDir: File, project: Project, sourceSet: SourceSet): Path =
    getOutDir(targetDir, getProjectName(project, sourceSet))

  private def getClassesDir(targetDir: File, projectName: String): Path =
    (targetDir / projectName / "build" / "classes").toPath

  private def getAndroidClassesDir(targetDir: File, project: Project, variant: BaseVariant): Path =
    getClassesDir(targetDir, getAndroidProjectName(project, variant))

  private def getClassesDir(targetDir: File, project: Project, sourceSet: SourceSet): Path =
    getClassesDir(targetDir, getProjectName(project, sourceSet))

  private def getSources(sourceSet: SourceSet): List[Path] =
    sourceSet.getAllJava.getSrcDirs.asScala.map(_.toPath).toList

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
      artifacts: List[ResolvedArtifactResult]
  ): Try[Option[Config.Scala]] = {
    def isJavaOnly: Boolean = {
      val allSourceFiles = sourceSet.getAllSource.getFiles.asScala.toList
      !allSourceFiles.filter(f => f.exists && f.isFile).exists(_.getName.endsWith(".scala"))
    }

    // Finding the compiler group and version from the standard Scala library added as dependency
    // library precedence is: user-specified, Scala3, Scala2 (as multiple library version may exist)
    val stdLibNames =
      parameters.stdLibName.map(List.apply(_)).getOrElse(List("scala-library", "scala3-library_3"))
    val artifactIds = artifacts
      .map(_.getId)
      .collect({ case mcai: ModuleComponentArtifactIdentifier => mcai })
    val stdLibIds = stdLibNames.flatMap(stdLibName =>
      artifactIds.find(_.getComponentIdentifier.getModule == stdLibName)
    )
    stdLibIds.headOption match {
      case Some(stdLibArtifact) =>
        val scalaCompileTaskName = sourceSet.getCompileTaskName("scala")
        val scalaCompileTask = project.getTask[ScalaCompile](scalaCompileTaskName)

        if (scalaCompileTask != null) {
          val scalaVersion = stdLibArtifact.getComponentIdentifier.getVersion
          val scalaOrg = stdLibArtifact.getComponentIdentifier.getGroup
          val scalaJars = scalaCompileTask.getScalaClasspath.asScala.map(_.toPath).toList
          val opts = scalaCompileTask.getScalaCompileOptions
          val options = optionList(opts) ++ getPluginsAsOptions(scalaCompileTask)
          val compilerName = parameters.compilerName.getOrElse("scala-compiler")
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
    val javaCompile = getJavaCompileTask(project, sourceSet)
    val options = javaCompile.getOptions
    getJavaConfig(project, javaCompile, options)
  }

  private def getJavaConfig(
      project: Project,
      javaCompile: JavaCompile,
      options: CompileOptions
  ): Option[Config.Java] = {
    val specs = new DefaultJavaCompileSpec()
    specs.setCompileOptions(options)
    specs.setSourceCompatibility(javaCompile.getSourceCompatibility)
    specs.setTargetCompatibility(javaCompile.getTargetCompatibility)
    if (options.getAnnotationProcessorPath != null)
      specs.setAnnotationProcessorPath(options.getAnnotationProcessorPath.asScala.toList.asJava);

    val builder = new JavaCompilerArgumentsBuilder(specs)
      .includeMainOptions(true)
      .includeClasspath(false)
      .includeSourceFiles(false)
      .includeLauncherOptions(false)

    var args = builder.build().asScala.toList

    if (!args.contains("--release")) {
      if (
        !args.contains("-source") &&
        !args.contains("--source") &&
        specs.getSourceCompatibility != null
      ) {
        args = "-source" :: specs.getSourceCompatibility :: args
      }

      if (
        !args.contains("-target") &&
        !args.contains("--target") &&
        specs.getTargetCompatibility != null
      ) {
        args = "-target" :: specs.getTargetCompatibility :: args
      }
    }

    // if annotation processor is not configured to run we remove the source
    if (args.contains("-proc:none") && args.contains("-s")) {
      args = args.takeWhile(_ != "-s") ++ args.dropWhile(_ != "-s").drop(2)
    } else if (args.contains("-s")) {
      Files.createDirectories(Paths.get(args(args.indexOf("-s") + 1)))
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

  private def getAllBloopCapableProjects(rootProject: Project): List[Project] = {
    rootProject.getAllprojects().asScala.filter(PluginUtils.canRunBloop).toList
  }
}

object BloopConverter {
  case class SourceSetDep(bloopModuleName: String, classesDir: Path)
}
