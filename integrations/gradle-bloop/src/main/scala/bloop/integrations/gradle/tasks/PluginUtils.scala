package bloop.integrations.gradle.tasks

import org.gradle.api.Project

trait PluginUtils {
  val project: Project

  def canRunBloop: Boolean = PluginUtils.canRunBloop(project)

  def hasJavaScalaPlugin: Boolean = PluginUtils.hasJavaScalaPlugin(project)

  def hasAndroidPlugin: Boolean = PluginUtils.hasAndroidPlugin(project)
}

object PluginUtils {
  def canRunBloop(project: Project): Boolean = {
    hasJavaScalaPlugin(project) ||
    hasAndroidPlugin(project)
  }
  def hasJavaScalaPlugin(project: Project): Boolean = {
    val pluginManager = project.getPluginManager
    pluginManager.hasPlugin("scala") ||
    pluginManager.hasPlugin("java")
  }
  def hasAndroidPlugin(project: Project): Boolean = {
    val pluginManager = project.getPluginManager
    pluginManager.hasPlugin("com.android.library") ||
    pluginManager.hasPlugin("com.android.application")
  }
}
