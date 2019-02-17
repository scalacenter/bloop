package bloop.integrations.gradle.tasks

import java.io.File
import java.nio.file.Files

import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.model.BloopConverter
import bloop.integrations.gradle.model.BloopConverter.SourceSetDep
import bloop.integrations.gradle.syntax._
import org.gradle.api.tasks.{SourceSet, TaskAction}
import org.gradle.api.{DefaultTask, Project}

import scala.util.{Failure, Success}

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
  private val parameters: BloopParameters = project.getExtension[BloopParameters]
  private val converter = new BloopConverter(parameters)

  @TaskAction
  def run(): Unit = {
    if (canRunBloop) runBloopPlugin()
    else {
      info(s"Ignoring 'bloopInstall' on non-Scala and non-Java project '${project.getName}'")
    }
  }

  def runBloopPlugin(): Unit = {
    val targetDir: File = parameters.targetDir
    info(s"Generating Bloop configuration to ${targetDir.getAbsolutePath}")

    if (!targetDir.exists()) {
      debug(s"Creating target directory ${targetDir}")
      Files.createDirectory(targetDir.toPath)
    }

    val mainSourceSet = project.getSourceSet(SourceSet.MAIN_SOURCE_SET_NAME)
    val otherSourceSets = project.allSourceSets.filter(_.getName != SourceSet.MAIN_SOURCE_SET_NAME)

    // The 'main' source set maps to the raw project name (as all integrations do)
    val mainProjectName = converter.getProjectName(project, mainSourceSet)
    generateBloopConfiguration(mainProjectName, Nil, mainSourceSet, targetDir, true)

    // Hardcode an implicit dependency for every source set to the main source set (compile) if it exists
    val strictDependencies =  List(SourceSetDep(mainProjectName, converter.getClassesDir(project, mainSourceSet)))

    // Generate the bloop configuration files for the rest of the source sets
    for (sourceSet <- otherSourceSets) {
      val projectName = converter.getProjectName(project, sourceSet)
      generateBloopConfiguration(projectName, strictDependencies, sourceSet, targetDir, false)
    }
  }

  private def generateBloopConfiguration(
      projectName: String,
      projectDependencies: List[SourceSetDep],
      sourceSet: SourceSet,
      targetDir: File,
      mandatory: Boolean
  ): Unit = {
    val targetFile = targetDir / s"$projectName.json"
    // Let's keep the error message as similar to the one in the sbt plugin as possible
    info(s"Generated ${targetFile.getAbsolutePath}")
    converter.toBloopConfig(projectDependencies, project, sourceSet, targetDir) match {
      case Failure(reason) if mandatory =>
        throw reason
      case Failure(reason) =>
        info(s"Skipping ${project.getName}/${sourceSet.getName} because: $reason")
      case Success(bloopConfig) =>
        bloop.config.write(bloopConfig, targetFile.toPath)
    }
  }
}
