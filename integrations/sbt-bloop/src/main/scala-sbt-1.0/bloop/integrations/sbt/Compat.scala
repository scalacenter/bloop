package bloop.integrations.sbt

import bloop.config.Config

import sbt.Def
import sbt.Task
import sbt.Defaults
import sbt.Classpaths
import sbt.io.syntax.File
import sbt.internal.util.Attributed
import sbt.{Artifact, Exec, Keys, SettingKey}
import sbt.librarymanagement.ScalaModuleInfo
import xsbti.compile.CompileAnalysis

object Compat {
  type PluginData = sbt.PluginData
  val PluginData = sbt.PluginData
  val PluginDiscovery = sbt.internal.PluginDiscovery
  val PluginManagement = sbt.internal.PluginManagement

  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }

  def currentCommandFromState(s: sbt.State): Option[String] =
    s.currentCommand.map(_.commandLine)

  implicit def execToString(e: Exec): String = e.commandLine

  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)

  def generateCacheFile(s: Keys.TaskStreams, id: String) =
    s.cacheStoreFactory make id

  def toBloopArtifact(a: Artifact, f: File): Config.Artifact = {
    val checksum = a.checksum.map(c => Config.Checksum(c.digest, c.`type`))
    Config.Artifact(a.name, a.classifier, checksum, f.toPath)
  }

  private final val anyWriter = implicitly[sbt.util.OptJsonWriter[AnyRef]]
  def toAnyRefSettingKey(id: String, m: Manifest[AnyRef]): SettingKey[AnyRef] =
    SettingKey(id)(m, anyWriter)

  lazy val compileSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopCompile := bloopCompile.value,
    Keys.internalDependencyClasspath := bloopInstallInternalClasspath.value,
    Keys.compile := Def.taskDyn {
      val isMetaBuild = BloopKeys.bloopIsMetaBuild.value
      if (isMetaBuild) Defaults.compileTask
      else bloopCompile
    }.value
  )

  final lazy val bloopInstallInternalClasspath: Def.Initialize[Task[Seq[Attributed[File]]]] = {
    Def.taskDyn {
      val currentProject = Keys.thisProjectRef.value
      val data = Keys.settingsData.value
      val deps = Keys.buildDependencies.value
      val conf = Keys.classpathConfiguration.value
      val self = Keys.configuration.value

      import scala.collection.JavaConverters._
      val visited = Classpaths.interSort(currentProject, conf, data, deps)
      val productDirs = (new java.util.LinkedHashSet[Task[Seq[File]]]).asScala
      val generatedFiles = (new java.util.LinkedHashSet[Task[Option[File]]]).asScala
      for ((dep, c) <- visited) {
        if ((dep != currentProject) || (conf.name != c && self.name != c)) {
          val classpathKey = Keys.productDirectories in (dep, sbt.ConfigKey(c))
          productDirs += classpathKey.get(data).getOrElse(sbt.std.TaskExtra.constant(Nil))
          val bloopKey = BloopKeys.bloopGenerate in (dep, sbt.ConfigKey(c))
          generatedFiles += bloopKey.get(data).getOrElse(sbt.std.TaskExtra.constant(None))
        }
      }

      val generatedTask = productDirs.toList.join.map(_.flatten.distinct).flatMap { dirs =>
        generatedFiles.toList.join.map(_.flatten.distinct).map { b =>
          val analysis = sbt.internal.inc.Analysis.Empty
          dirs.map { dir =>
            Attributed.blank(dir).put(Keys.analysis, analysis)
          }
        }
      }

      Def.task(generatedTask.value)
    }
  }

  private final val StableDef = Def // Needed to circumvent buggy dynamic reference detection in sbt
  lazy val bloopCompile: Def.Initialize[Task[CompileAnalysis]] = Def.taskDyn {
    val targetName = BloopKeys.bloopTargetName.value
    import sbt.internal.util.AttributeKey
    val configDir = BloopKeys.bloopConfigDir.value.getParentFile
    val internalClasspath = Keys.internalDependencyClasspath.value
    val runCompilation = StableDef.task[CompileAnalysis] {
      println(s"Compiling ${targetName} with bloop")
      scala.sys.process.Process(List("bloop", "compile", targetName), configDir).!!
      sbt.internal.inc.Analysis.Empty
    }

    StableDef.sequential(
      BloopKeys.bloopGenerate,
      runCompilation
    )
  }

}
