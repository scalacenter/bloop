package bloop.build.integrations

import java.io.File

import bloop.integrations.sbt.{BloopKeys, BloopPlugin => SbtBloopPlugin}
import sbt.{AutoPlugin, Def, PluginTrigger, Plugins, Keys, ThisBuild, Global}

object IntegrationPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin && SbtBloopPlugin
  val autoImport = PluginKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    PluginImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    PluginImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    PluginImplementation.projectSettings
}

object PluginKeys {
  val schemaVersion = Def.settingKey[String]("The schema version")
  val enableIndexCreation = Def.settingKey[Boolean]("Enable index creation")
  val integrationIndex = Def.settingKey[Map[String, File]]("Map of project names and bloop dirs.")
  val buildIndex = Def.taskKey[Unit]("Write our builds to the build index.")
  val cleanAllBuilds = Def.taskKey[Unit]("Clean all builds.")
}

object PluginImplementation {
  def globalSettings: Seq[Def.Setting[_]] = List(
    PluginKeys.schemaVersion := {
      Option(System.getProperty("bloop.integrations.schemaVersion"))
        .getOrElse(sys.error("Schema version is missing!"))
    },
    // Very important to avoid wrong export in community build
    BloopKeys.bloopAggregateSourceDependencies := false
  )

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
          .mkString("", System.lineSeparator, System.lineSeparator)
        sbt.IO.append(targetFile, newContents)
      } else ()
    },
    PluginKeys.cleanAllBuilds := {
      val log = Keys.streams.value.log
      PluginKeys.integrationIndex.value.iterator.foreach {
        case (key, configDir) =>
          log.info(s"Deleting bloop config directory ${configDir.getCanonicalFile.getAbsolutePath}")
          sbt.IO.delete(configDir)
      }
    },
    BloopKeys.bloopConfigDir := {
      val oldConfigDir = BloopKeys.bloopConfigDir.value
      val schemaVersion = PluginKeys.schemaVersion.in(Global).value
      new sbt.RichFile(oldConfigDir)./(schemaVersion)
    }
  )
}
