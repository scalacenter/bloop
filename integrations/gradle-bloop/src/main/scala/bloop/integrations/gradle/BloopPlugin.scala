package bloop.integrations.gradle

import bloop.integrations.gradle.tasks.{BloopInstallTask, ConfigureBloopInstallTask, TaskLogging}
import org.gradle.api.{Plugin, Project}
import syntax._

/**
 * Main entry point of the gradle bloop plugin.
 *
 * The bloop plugin defines two tasks:
 *
 * 1. `configureBloopInstall`: responsible to set up the environment and force artifact resolution.
 * 2. `bloopInstall`: responsible of generating the bloop config files from the configured data.
 *
 * The second task depends on the first one so that this data dependency is always met.
 */
final class BloopPlugin extends Plugin[Project] {
  override def apply(project: Project): Unit = {
    project.getLogger.info(s"Applying bloop plugin to project ${project.getName}", Seq.empty: _*)
    project.createExtension[BloopParameters]("bloop", project)

    // Creates two tasks: one to configure the plugin and the other one to generate the config files
    val configureBloopInstall =
      project.createTask[ConfigureBloopInstallTask]("configureBloopInstall")
    val bloopInstall = project.createTask[BloopInstallTask]("bloopInstall")
    configureBloopInstall.installTask = Some(bloopInstall)
    bloopInstall.dependsOn(configureBloopInstall)
    ()
  }
}
