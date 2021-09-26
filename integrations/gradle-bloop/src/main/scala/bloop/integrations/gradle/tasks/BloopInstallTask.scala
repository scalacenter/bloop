package bloop.integrations.gradle.tasks

import java.io.File
import java.nio.file.Files

import bloop.integrations.gradle.BloopParametersExtension
import bloop.integrations.gradle.model.BloopConverter
import bloop.integrations.gradle.model.BloopConverter.SourceSetDep
import bloop.integrations.gradle.syntax._
import org.gradle.api.tasks.{SourceSet, TaskAction}
import org.gradle.api.{DefaultTask, Project}
import com.android.builder.model.SourceProvider
import com.android.build.gradle.api.BaseVariant

import scala.collection.JavaConverters._
import scala.util.{Failure, Success}
import java.nio.file.FileAlreadyExistsException

/**
 * Define a Gradle task that generates bloop configuration files from a Gradle project.
 *
 * This part of the plugin is mainly in charge of handling source sets. Source sets are logical
 * group of sources and resources ([[https://docs.gradle.org/current/dsl/org.gradle.api.tasks.SourceSet.html]]).
 *
 * Source sets are the equivalent of sbt configurations (approximately). The default java plugin
 * adds two source sets by default (compile and test) [[https://docs.gradle.org/current/userguide/java_plugin.html]].
 */
class BloopInstallTask extends DefaultTask with PluginUtils with TaskLogging {
  override val project: Project = getProject
  private val extension: BloopParametersExtension = project.getExtension[BloopParametersExtension]

  @TaskAction
  def run(): Unit = {
    if (canRunBloop) runBloopPlugin()
    else {
      info(
        s"Ignoring 'bloopInstall' on non-Scala, non-Java, non-Android project '${project.getName}'"
      )
    }
  }

  def runBloopPlugin(): Unit = {
    val parameters = extension.createParameters
    val converter = new BloopConverter(parameters, info)
    val targetDir: File = parameters.targetDir
    info(s"Generating Bloop configuration to ${targetDir.getAbsolutePath}")

    if (!targetDir.exists()) {
      debug(s"Creating target directory ${targetDir}")
      try {
        Files.createDirectory(targetDir.toPath)
      } catch {
        case _: FileAlreadyExistsException => // do nothing - parallel exports cause this
      }
    }

    if (hasJavaScalaPlugin)
      ScalaJavaInstall.install(project, targetDir, converter, info)

    if (hasAndroidPlugin)
      AndroidInstall.install(project, targetDir, converter, info)
  }
}

object ScalaJavaInstall {

  def install(
      project: Project,
      targetDir: File,
      converter: BloopConverter,
      info: String => Unit
  ): Unit = {
    for (sourceSet <- project.allSourceSets) {
      val projectName = converter.getProjectName(project, sourceSet)
      generateBloopConfiguration(
        project,
        projectName,
        sourceSet,
        targetDir,
        converter,
        info
      )
    }
  }

  private def generateBloopConfiguration(
      project: Project,
      projectName: String,
      sourceSet: SourceSet,
      targetDir: File,
      converter: BloopConverter,
      info: String => Unit
  ): Unit = {
    val targetFile = targetDir / s"$projectName.json"
    // Let's keep the error message as similar to the one in the sbt plugin as possible
    info(s"Generated ${targetFile.getAbsolutePath}")
    converter.toBloopConfig(projectName, project, sourceSet, targetDir) match {
      case Failure(reason) =>
        info(s"Skipping ${project.getName}/${sourceSet.getName} because: $reason")
      case Success(bloopConfig) =>
        bloop.config.write(bloopConfig, targetFile.toPath)
    }
  }
}

object AndroidInstall {

  def install(
      project: Project,
      targetDir: File,
      converter: BloopConverter,
      info: String => Unit
  ): Unit = {
    for (variant <- project.androidVariants) {
      generateBloopConfiguration(project, variant, targetDir, converter, info)
      val testVariant = variant.getTestVariant()
      if (testVariant != null)
        generateBloopConfiguration(project, testVariant, targetDir, converter, info)
    }
  }

  private def generateBloopConfiguration(
      project: Project,
      variant: BaseVariant,
      targetDir: File,
      converter: BloopConverter,
      info: String => Unit
  ): Unit = {
    val projectName = converter.getAndroidProjectName(project, variant)
    generateBloopConfiguration(
      project,
      projectName,
      variant,
      variant.getSourceSets.asScala.toList,
      targetDir,
      converter,
      info
    )
  }

  private def generateBloopConfiguration(
      project: Project,
      projectName: String,
      variant: BaseVariant,
      sourceProviders: List[SourceProvider],
      targetDir: File,
      converter: BloopConverter,
      info: String => Unit
  ): Unit = {
    val targetFile = targetDir / s"$projectName.json"
    info(s"Generated ${targetFile.getAbsolutePath}")
    converter.toBloopConfig(projectName, project, variant, sourceProviders, targetDir) match {
      case Failure(reason) =>
        info(s"Skipping ${project.getName} because: $reason")
      case Success(bloopConfig) =>
        bloop.config.write(bloopConfig, targetFile.toPath)
    }
  }
}
