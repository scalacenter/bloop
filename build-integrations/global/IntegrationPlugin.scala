package bloop.build.integrations

import java.io.File

import bloop.integrations.{sbt => sbtbloop}
import bloop.integrations.sbt.{AutoImported => BloopKeys}
import sbt.{AutoPlugin, Def, Keys, PluginTrigger, Plugins, State, Task, ThisBuild, Global}

class IntegrationPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin
  val autoImport = PluginKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    PluginImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    PluginImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    PluginImplementation.projectSettings
}

object PluginKeys {
  val copyConfigs = Def.taskKey[Unit]("Copy bloop configurations")
}

object PluginImplementation {
  def globalSettings: Seq[Def.Setting[_]] = Nil
  def buildSettings: Seq[Def.Setting[_]] = Nil
  def projectSettings: Seq[Def.Setting[_]] = Nil

  object PluginDefaults {}
}
