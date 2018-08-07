package bloop.integrations.gradle.tasks

import org.gradle.api.Project

trait PluginUtils {
  val project: Project

  def canRunBloop: Boolean = {
    val pluginManager = project.getPluginManager
    pluginManager.hasPlugin("scala") || pluginManager.hasPlugin("java")
  }
}
