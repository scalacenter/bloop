package bloop.integrations.gradle.tasks

import java.io.File
import java.nio.file.Files

import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.model.GradleToBloop
import bloop.integrations.gradle.syntax._
import org.gradle.api.tasks.{SourceSet, TaskAction}
import org.gradle.api.{DefaultTask, Project}

/**
  * Task to generate bloop's configuration JSONs
  */
class BloopInstallTask extends DefaultTask with TaskLogging {
  private val project: Project = getProject
  private val parameters: BloopParameters = project.getExtension[BloopParameters]

  @TaskAction
  def run(): Unit = {
    val targetDir: File = parameters.targetDir

    info(s"Generating Bloop configuration to ${targetDir.getAbsolutePath}")

    if (!targetDir.exists()) {
      debug(s"Creating target directory")
      Files.createDirectory(targetDir.toPath)
    }

    val mainSourceSet = project.getSourceSet(parameters.mainSourceSet)
    val otherSourceSets = project.allSourceSets.filter(_.getName != parameters.mainSourceSet)

    val converter = new GradleToBloop(parameters)

    // The 'main' source set is generated first to targetDir/projectName.json
    generateBloopConfiguration(
      converter,
      Set.empty,
      mainSourceSet,
      targetDir)

    // All the other source sets are generated to targetDir/projectName-sourceSet.json with the main
    // source set added as dependency
    // NOTE: this dependency is hardcoded in the plugin currently, not inferred from Gradle's project model
    for (sourceSet <- otherSourceSets) {
      generateBloopConfiguration(
        converter,
        Set(converter.getProjectName(project, mainSourceSet)),
        sourceSet,
        targetDir)
    }
  }

  private def generateBloopConfiguration(converter: GradleToBloop,
                                         dependencies: Set[String],
                                         sourceSet: SourceSet,
                                         targetDir: File): Unit = {

    val name = converter.getProjectName(project, sourceSet)
    val targetFile = targetDir / s"$name.json"

    info(s"Generating Bloop file ${targetFile.getAbsolutePath}...")

    val bloopConfig = converter.toBloopConfig(
      dependencies,
      project,
      sourceSet,
      targetDir)
    bloop.config.write(bloopConfig, targetFile.toPath)
  }
}

