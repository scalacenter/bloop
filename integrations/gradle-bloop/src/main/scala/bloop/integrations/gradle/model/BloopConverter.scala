package bloop.integrations.gradle.model

import java.io.File
import java.nio.file.Path

import bloop.config.Config
import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.syntax._
import org.gradle.api
import org.gradle.api.{GradleException, Project}
import org.gradle.api.artifacts.{ProjectDependency, ResolvedArtifact}
import org.gradle.api.internal.tasks.compile.{DefaultJavaCompileSpec, JavaCompilerArgumentsBuilder}
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.{ScalaCompile, ScalaCompileOptions}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Define the conversion from Gradle's project model to Bloop's project model.
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
   * @param strictProjectDependencies Additional dependencies cannot be inferred from Gradle's object model
   * @param project The Gradle project model
   * @param sourceSet The source set to convert
   * @param targetDir Target directory for bloop files
   * @return Bloop configuration
   */
  def toBloopConfig(
      strictProjectDependencies: List[String],
      project: Project,
      sourceSet: SourceSet,
      targetDir: File
  ): Try[Config.File] = {
    val configuration = project.getConfiguration(sourceSet.getCompileConfigurationName)
    val artifacts: List[ResolvedArtifact] =
      configuration.getResolvedConfiguration.getResolvedArtifacts.asScala.toList

    // We cannot turn this into a set directly because we need the topological order for correctness
    val projectDependencies: List[ProjectDependency] =
      configuration.getAllDependencies.asScala.collect { case dep: ProjectDependency => dep }.toList
    val projectDependenciesIds = projectDependencies.map { dep =>
      val project = dep.getDependencyProject
      getProjectName(project, project.getSourceSet(parameters.mainSourceSet))
    }

    // Strict project dependencies should have more priority than regular project dependencies
    val allDependencies = strictProjectDependencies ++ projectDependenciesIds
    val dependencyClasspath: List[ResolvedArtifact] = artifacts
      .filter(resolvedArtifact => !isProjectDependency(projectDependencies, resolvedArtifact))

    val classpath: List[Path] = {
      val projectDependencyClassesDirs =
        projectDependencies.map(dep => getClassesDir(dep.getDependencyProject, sourceSet))
      (projectDependencyClassesDirs ++ dependencyClasspath.map(_.getFile)).map(_.toPath).toList
    }

    for {
      scalaConfig <- getScalaConfig(project, sourceSet, artifacts)
      resolution = Config.Resolution(dependencyClasspath.map(artifactToConfigModule).toList)
      bloopProject = Config.Project(
        name = getProjectName(project, sourceSet),
        directory = project.getProjectDir.toPath,
        sources = getSources(sourceSet),
        dependencies = allDependencies.toList,
        classpath = classpath,
        out = project.getBuildDir.toPath,
        classesDir = getClassesDir(project, sourceSet).toPath,
        `scala` = scalaConfig,
        java = getJavaConfig(project, sourceSet),
        sbt = None,
        test = Some(Config.Test(testFrameworks, defaultTestOptions)), // TODO: make this configurable?
        platform = None,
        resolution = Some(resolution)
      )
    } yield Config.File(Config.File.LatestVersion, bloopProject)
  }

  def getProjectName(project: Project, sourceSet: SourceSet): String = {
    if (sourceSet.getName == parameters.mainSourceSet) {
      project.getName
    } else {
      s"${project.getName}-${sourceSet.getName}"
    }
  }

  private def getClassesDir(project: Project, sourceSet: SourceSet): File =
    project.getBuildDir / "classes" / "scala" / sourceSet.getName

  private def getSources(sourceSet: SourceSet): List[Path] =
    sourceSet.getAllSource.asScala.map(_.toPath).toList

  private def isProjectDependency(
      projectDependencies: List[ProjectDependency],
      resolvedArtifact: ResolvedArtifact
  ): Boolean = {
    projectDependencies.exists(
      dep =>
        dep.getGroup == resolvedArtifact.getModuleVersion.getId.getGroup &&
          dep.getName == resolvedArtifact.getModuleVersion.getId.getName &&
          dep.getVersion == resolvedArtifact.getModuleVersion.getId.getVersion
    )
  }

  private def artifactToConfigModule(artifact: ResolvedArtifact): Config.Module = {
    Config.Module(
      organization = artifact.getModuleVersion.getId.getGroup,
      name = artifact.getName,
      version = artifact.getModuleVersion.getId.getVersion,
      configurations = None,
      List(
        Config.Artifact(
          name = artifact.getModuleVersion.getId.getName,
          classifier = Option(artifact.getClassifier),
          checksum = None,
          path = artifact.getFile.toPath
        )
      )
    )
  }

  private def getScalaConfig(
      project: Project,
      sourceSet: SourceSet,
      artifacts: List[ResolvedArtifact]
  ): Try[Option[Config.Scala]] = {
    def isJavaOnly: Boolean = sourceSet.getAllSource.asScala.forall(!_.getName.endsWith(".scala"))

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
          val options = optionSet(opts).toList
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
        val target = s" project ${project.getName}/${sourceSet.getName}"
        val artifactNames = artifacts.map(_.getName).mkString("\n")
        Failure(
          new GradleException(
            s"Expected Scala standard library in classpath of $target that contains Scala sources. Found artifacts:\n$artifactNames."
          )
        )
    }
  }

  private def getJavaConfig(project: Project, sourceSet: SourceSet): Option[Config.Java] = {
    val javaCompileTaskName = sourceSet.getCompileTaskName("java")
    val javaCompileTask = project.getTask[JavaCompile](javaCompileTaskName)
    val opts = javaCompileTask.getOptions

    val specs = new DefaultJavaCompileSpec()
    specs.setCompileOptions(opts)

    val builder = new JavaCompilerArgumentsBuilder(specs)
      .includeMainOptions(true)
      .includeClasspath(false)
      .includeSourceFiles(false)
      .includeLauncherOptions(false)
    val args = builder.build().asScala.toList

    // Always return a java configuration (this cannot hurt us)
    Some(Config.Java(args))
  }

  private def ifEnabled[T](option: Boolean)(value: T): Option[T] =
    if (option) Some(value) else None

  private def optionSet(options: ScalaCompileOptions): Set[String] = {
    // based on ZincScalaCompilerArgumentsGenerator
    val baseOptions: Set[String] = Seq(
      ifEnabled(options.isDeprecation)("-deprecation"),
      ifEnabled(options.isUnchecked)("-unchecked"),
      ifEnabled(options.isOptimize)("-optimize"),
      ifEnabled(options.getDebugLevel == "verbose")("-verbose"),
      ifEnabled(options.getDebugLevel == "debug")("-Ydebug"),
      Option(options.getEncoding).map(encoding => s"-encoding $encoding"),
      Option(options.getDebugLevel).map(level => s"-g:$level")
    ).flatten.toSet

    val loggingPhases: Set[String] =
      Option(options.getLoggingPhases)
        .map(_.asScala.toSet)
        .getOrElse(Set.empty)
        .map(phase => s"-Ylog:$phase")

    val additionalOptions: Set[String] =
      mergeEncodingOption(options.getAdditionalParameters.asScala.toList).toSet

    baseOptions.union(loggingPhases).union(additionalOptions)
  }

  private def mergeEncodingOption(values: List[String]): List[String] =
    values match {
      case "-encoding" :: charset :: rest =>
        s"-encoding $charset" :: mergeEncodingOption(rest)
      case value :: rest =>
        value :: mergeEncodingOption(rest)
      case Nil =>
        Nil
    }

  private val scalaCheckFramework = Config.TestFramework(
    List(
      "org.scalacheck.ScalaCheckFramework"
    ))

  private val scalaTestFramework = Config.TestFramework(
    List(
      "org.scalatest.tools.Framework",
      "org.scalatest.tools.ScalaTestFramework"
    )
  )

  private val specsFramework = Config.TestFramework(
    List(
      "org.specs.runner.SpecsFramework",
      "org.specs2.runner.Specs2Framework",
      "org.specs2.runner.SpecsFramework"
    )
  )

  private val jUnitFramework = Config.TestFramework(
    List(
      "com.novocode.junit.JUnitFramework"
    )
  )

  private val testFrameworks: List[Config.TestFramework] =
    List(scalaCheckFramework, scalaTestFramework, specsFramework, jUnitFramework)
  private val defaultTestOptions =
    Config.TestOptions(Nil, List(Config.TestArgument(List("-v", "-a"), Some(jUnitFramework))))
}
