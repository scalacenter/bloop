package ch.epfl.scala.sbt.maven

import sbt.{AutoPlugin, Command, Def, Keys, PluginTrigger, Plugins}

class MavenIntegrationPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = sbt.plugins.JvmPlugin
  val autoImport = MavenPluginKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    MavenPluginImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    MavenPluginImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    MavenPluginImplementation.projectSettings
}

object MavenPluginKeys {
  val mavenPlugin = settingKey[Boolean](
    "If true, enables adding maven as a dependency and auto-generation of the plugin descriptor file.")
}

object MavenPluginImplementation {
  val globalSettings: Seq[Def.Setting[_]] = Nil
  val buildSettings: Seq[Def.Setting[_]] = Nil
  val projectSettings: Seq[Def.Setting[_]] = List(
    MavenPluginKeys.mavenPlugin := false,
    Keys.resourceGenerators += MavenPluginDefaults.resourceGenerators.taskValue
  )

  object MavenPluginDefaults {
    import sbt.{Task, File}
    val resourceGenerators: Def.Initialize[Task[Seq[File]]] = Def.taskDyn {
      if (!MavenPluginKeys.mavenPlugin) Def.task(())
      else {
        Def.task {

        }
      }
    }
  }
}
