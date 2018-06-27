package bloop.integrations.gradle.tasks

import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.syntax._
import org.gradle.api.tasks.{SourceSet, TaskAction}
import org.gradle.api.{DefaultTask, GradleException, Project, Task}

import scala.collection.JavaConverters._

/**
  * Task to set the bloopInstall tasks's inputs
  *
  * The bloopInstall task depends on the dependency resolution, so it cannot decide its inputs
  * (used by the up-to-date checker) in configuration time. This task always runs and in build
  * time uses the resolved artifacts and the source sets to set up the associated bloopInstall
  * tasks's input dependencies.
  */
class ConfigureBloopInstallTask extends DefaultTask with TaskLogging {
  private val project: Project = getProject
  private val parameters: BloopParameters = project.getExtension[BloopParameters]

  /** The install task to set runtime, automatically set at plugin application */
  var installTask: Option[Task] = None

  @TaskAction
  def run(): Unit = {
    installTask match {
      case Some(task) =>
        project.allSourceSets.foreach(addSourceSetAsInputs(task, _))

        // bloopInstall also depends on all the properties on the 'bloop' parameters
        task.getInputs.property("targetDir", parameters)
        task.getInputs.property("mainSourceSet", parameters)
        task.getInputs.property("compilerName", parameters)
      case None =>
        throw new GradleException("installTask property must be specified on configureBloopInstall task")
    }
  }

  /**
    * Adds both the sources and the source set's configuration's resolved dependencies to the
    * given tasks' inputs
    */
  private def addSourceSetAsInputs(task: Task, sourceSet: SourceSet): Unit = {
    val configuration = project.getConfiguration(sourceSet.getCompileConfigurationName)

    val artifacts = configuration.getResolvedConfiguration.getResolvedArtifacts.asScala
    for (artifact <- artifacts) {
      debug(s"Artifact added as input: ${artifact.getFile.getAbsolutePath}")
      task.getInputs.file(artifact.getFile)
    }

    for (source <- sourceSet.getAllSource.asScala) {
      debug(s"Source added as input: ${source.getAbsolutePath}")
      task.getInputs.file(source)
    }
  }
}

