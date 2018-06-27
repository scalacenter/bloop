package bloop.integrations.gradle

import bloop.integrations.gradle.tasks.{BloopInstallTask, ConfigureBloopInstallTask}
import org.gradle.api.{Plugin, Project}
import syntax._

/**
  * Main entry point of the gradle bloop plugin
  */
class BloopPlugin extends Plugin[Project] {
  override def apply(project: Project): Unit = {
    project.getLogger.info(s"Applying bloop plugin for project ${project.getName}", Seq.empty : _*)

    project.createExtension[BloopParameters]("bloop", project)

    val configureBloopInstall = project.createTask[ConfigureBloopInstallTask]("configureBloopInstall")
    val bloopInstall = project.createTask[BloopInstallTask]("bloopInstall")
    configureBloopInstall.installTask = Some(bloopInstall)

    bloopInstall.dependsOn(configureBloopInstall)
  }
}
