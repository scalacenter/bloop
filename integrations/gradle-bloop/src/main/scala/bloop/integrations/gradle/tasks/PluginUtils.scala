package bloop.integrations.gradle.tasks

import org.gradle.api.Project

trait PluginUtils {
  val project: Project

  def canRunBloop: Boolean = PluginUtils.canRunBloop(project)
}

object PluginUtils {
  def canRunBloop(project: Project): Boolean = {
    val pluginManager = project.getPluginManager
    pluginManager.hasPlugin("scala") || pluginManager.hasPlugin("java")
  }
}
