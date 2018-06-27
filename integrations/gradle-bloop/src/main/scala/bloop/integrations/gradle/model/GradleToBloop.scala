package bloop.integrations.gradle.model

import java.io.File
import java.nio.file.Path

import bloop.config.Config
import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.syntax._
import org.gradle.api.artifacts.{Configuration, ProjectDependency, ResolvedArtifact}
import org.gradle.api.internal.tasks.compile.{CommandLineJavaCompilerArgumentsGenerator, DefaultJavaCompileSpec, JavaCompilerArgumentsBuilder}
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.{ScalaCompile, ScalaCompileOptions}
import org.gradle.api.{GradleException, Project}

import scala.collection.JavaConverters._

/**
  * Conversion from Gradle's project model to Bloop's project model
  * @param parameters User-provided customization parameters
  */
class GradleToBloop(parameters: BloopParameters) {

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
    * @param additionalDependencies Additional dependencies cannot be inferred from Gradle's object model
    * @param project The Gradle project model
    * @param sourceSet The source set to convert
    * @param targetDir Target directory for bloop files
    * @return Bloop configuration
    */
  def toBloopConfig(additionalDependencies: Set[String],
                    project: Project,
                    sourceSet: SourceSet,
                    targetDir: File): Config.File = {

    val configuration = project.getConfiguration(sourceSet.getCompileConfigurationName)

    val artifacts: Set[ResolvedArtifact] =
      configuration.getResolvedConfiguration.getResolvedArtifacts.asScala.toSet

    val projectDependencies: Set[ProjectDependency] =
      configuration.getAllDependencies.asScala.collect { case dep: ProjectDependency => dep }.toSet

    val allDependencies: Set[String] =
      projectDependencies
        .map(dep => getProjectName(dep.getDependencyProject, dep.getDependencyProject.getSourceSet(parameters.mainSourceSet)))
        .union(additionalDependencies)

    val nonProjectDependencyArtifacts: Set[ResolvedArtifact] = artifacts
      .filter(resolvedArtifact => !isAProjectDependency(projectDependencies, resolvedArtifact))

    val projectDependencyClassesDirs: Set[File] =
      projectDependencies.map(dep => getClassesDir(dep.getDependencyProject, sourceSet))

    val classpath: Array[Path] =
      nonProjectDependencyArtifacts.map(_.getFile).union(projectDependencyClassesDirs)
        .map(_.toPath)
        .toArray

    val bloopProject = Config.Project(
      name = getProjectName(project, sourceSet),
      directory = project.getProjectDir.toPath,
      sources = getSources(sourceSet),
      dependencies = allDependencies.toArray,
      classpath = classpath,
      out = project.getBuildDir.toPath,
      analysisOut = getAnalysisOut(project, sourceSet, targetDir).toPath,
      classesDir = getClassesDir(project, sourceSet).toPath,
      `scala` = getScalaConfig(project, sourceSet, artifacts),
      java = getJavaConfig(project, sourceSet),
      sbt = Config.Sbt.empty,
      test = Config.Test(testFrameworks, defaultTestOptions), // TODO: make this configurable?
      platform = Config.Platform.default,
      compileSetup = Config.CompileSetup.empty, // TODO: make this configurable?
      resolution = Config.Resolution(nonProjectDependencyArtifacts.map(artifactToConfigModule).toList)
    )

    Config.File(Config.File.LatestVersion, bloopProject)
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

  private def getAnalysisOut(project: Project, sourceSet: SourceSet, targetDir: File): File = {
    val name = getProjectName(project, sourceSet)
    targetDir / project.getName / s"$name-analysis.bin"
  }

  private def getSources(sourceSet: SourceSet): Array[Path] =
    sourceSet.getAllSource.asScala.map(_.toPath).toArray

  private def isAProjectDependency(projectDependencies: Set[ProjectDependency], resolvedArtifact: ResolvedArtifact): Boolean = {
    projectDependencies.exists(dep =>
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
      List(Config.Artifact(
        name = artifact.getModuleVersion.getId.getName,
        classifier = Option(artifact.getClassifier),
        checksum = None,
        path = artifact.getFile.toPath
      ))
    )
  }

  private def getScalaConfig(project: Project, sourceSet: SourceSet, artifacts: Set[ResolvedArtifact]): Config.Scala = {
    val scalaCompilerArtifact = artifacts
      .find(_.getName == parameters.compilerName)
      .getOrElse(throw new GradleException(s"${parameters.compilerName} is not added as dependency"))
    val scalaVersion = scalaCompilerArtifact.getModuleVersion.getId.getVersion
    val scalaGroup = scalaCompilerArtifact.getModuleVersion.getId.getGroup

    val scalaCompileTaskName = sourceSet.getCompileTaskName("scala")
    val scalaCompileTask = project.getTask[ScalaCompile](scalaCompileTaskName)
    val compilerClasspath = scalaCompileTask.getScalaClasspath.asScala.map(_.toPath).toArray

    val opts = scalaCompileTask.getScalaCompileOptions
    val compilerOptions = optionSet(opts).toArray

    Config.Scala(
      scalaGroup,
      parameters.compilerName,
      scalaVersion,
      compilerOptions,
      compilerClasspath
    )
  }

  private def getJavaConfig(project: Project, sourceSet: SourceSet): Config.Java = {
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
    val args = builder.build().asScala.toArray

    Config.Java(args)
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
      Option(options.getEncoding).map(encoding => s"--encoding $encoding"),
      Option(options.getDebugLevel).map(level => s"-g:$level")
    ).flatten.toSet

    val loggingPhases: Set[String] =
      Option(options.getLoggingPhases)
        .map(_.asScala.toSet)
        .getOrElse(Set.empty)
        .map(phase => s"-Ylog:$phase")

    val additionalOptions: Set[String] =
      options.getAdditionalParameters.asScala.toSet

    baseOptions union loggingPhases union additionalOptions
  }

  private val scalaCheckFramework = Config.TestFramework(
    List(
      "org.scalacheck.ScalaCheckFramework"
    ))

  private val scalaTestFramework = Config.TestFramework(
    List(
      "org.scalatest.tools.Framework",
      "org.scalatest.tools.ScalaTestFramework"
    ))

  private val specsFramework = Config.TestFramework(
    List(
      "org.specs.runner.SpecsFramework",
      "org.specs2.runner.Specs2Framework",
      "org.specs2.runner.SpecsFramework"
    ))

  private val jUnitFramework = Config.TestFramework(
    List(
      "com.novocode.junit.JUnitFramework"
    ))

  private val testFrameworks: Array[Config.TestFramework] = Array(
    scalaCheckFramework,
    scalaTestFramework,
    specsFramework,
    jUnitFramework
  )

  private val defaultTestOptions =
    Config.TestOptions(Nil, List(Config.TestArgument(Array("-v", "-a"), Some(jUnitFramework))))
}
