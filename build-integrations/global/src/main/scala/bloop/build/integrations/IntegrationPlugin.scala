package bloop.build.integrations

import java.io.File

import bloop.integrations.sbt.{AutoImported => BloopKeys}
import bloop.integrations.{sbt => sbtbloop}
import sbt.{AutoPlugin, Def, PluginTrigger, Plugins}

object IntegrationPlugin extends AutoPlugin {
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
  val enableIndexCreation = Def.settingKey[Boolean]("Enable index creation")
  val integrationIndex = Def.settingKey[Map[String, File]]("Map of project names and bloop dirs.")
  val buildIndex = Def.taskKey[Unit]("Write our builds to the build index.")
}

object PluginImplementation {
  def globalSettings: Seq[Def.Setting[_]] = Nil
  def buildSettings: Seq[Def.Setting[_]] = Nil
  def projectSettings: Seq[Def.Setting[_]] = List(
    PluginKeys.integrationIndex := Map.empty,
    PluginKeys.enableIndexCreation := false,
    PluginKeys.buildIndex := {
      if (PluginKeys.enableIndexCreation.value) {
        val target = Option(System.getProperty("bloop.integrations.index"))
          .getOrElse(sys.error("Missing bloop integrations index!"))
        val targetFile = sbt.file(target)
        val newContents = PluginKeys.integrationIndex.value
          .map { case (key, path) => s"$key,${path.getCanonicalFile.getAbsolutePath}" }
          .mkString(System.lineSeparator)
        sbt.IO.append(targetFile, s"${System.lineSeparator}${newContents}")
      } else ()
    }
  )
}
