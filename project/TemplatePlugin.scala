package build

import sbt.{Keys, Plugins, Setting, settingKey, taskKey}
import sbt.io.IO
import sbt.plugins.JvmPlugin

import java.io.File

object TemplatePlugin extends sbt.AutoPlugin {

  override def requires: Plugins = JvmPlugin

  final val variableMappings = settingKey[Map[String, Lazy]]("Mappings")
  final val templateMappings = settingKey[Map[File, File]]("scripts")
  final val makeTemplates = taskKey[Unit]("...")

  final val templateSettings: Seq[Setting[_]] = Seq(
    variableMappings := Map.empty,
    templateMappings := Map.empty,
    makeTemplates := {
      val logger = Keys.streams.value.log
      val mappings = variableMappings.value
      val toGenerate = templateMappings.value
      toGenerate.foreach {
        case (source, target) =>
          make(mappings, source, target)
          logger.info(s"Generated $target from $source")
      }
    }
  )

  override def projectSettings: Seq[Setting[_]] = templateSettings

  implicit class Lazy(value: => String) {
    def get(): String = value
  }

  private def replaceAll(mappings: Map[String, Lazy]): String => String =
    mappings.foldLeft(identity[String] _) {
      case (fn, (variable, value)) =>
        in =>
          fn(in).replaceAll(s"#$variable#", value.get())
    }

  private def make(mappings: Map[String, Lazy], source: File, target: File): Unit = {
    val replaceFn = replaceAll(mappings)
    val originalContent = IO.read(source)
    val newContent = replaceFn(originalContent)
    IO.write(target, newContent)
  }

}
