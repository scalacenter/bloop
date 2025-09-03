package bloop.integrations.sbt

import bloop.config.Config

import sbt.Artifact
import sbt.Def
import sbt.Exec
import sbt.Keys
import sbt.SettingKey
import sbt.io.syntax.File
import sbt.librarymanagement.ScalaModuleInfo
import sbt.util.CacheStore

object Compat {
  type CompileAnalysis = xsbti.compile.CompileAnalysis
  type PluginData = sbt.PluginData
  val PluginData = sbt.PluginData
  val PluginDiscovery = sbt.internal.PluginDiscovery
  val PluginManagement = sbt.internal.PluginManagement
  type CompileResult = xsbti.compile.CompileResult

  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }

  def currentCommandFromState(s: sbt.State): Option[String] =
    s.currentCommand.map(_.commandLine)

  implicit def execToString(e: Exec): String = e.commandLine

  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)

  def generateCacheFile(s: Keys.TaskStreams, id: String): CacheStore =
    s.cacheStoreFactory make id

  def toBloopArtifact(a: Artifact, f: File): Config.Artifact = {
    val checksum = a.checksum.map(c => Config.Checksum(c.digest, c.`type`))
    Config.Artifact(a.name, a.classifier, checksum, f.toPath)
  }

  private final val anyWriter = implicitly[sbt.util.OptJsonWriter[AnyRef]]
  def toAnyRefSettingKey(id: String, m: Manifest[AnyRef]): SettingKey[AnyRef] =
    SettingKey(id)(m, anyWriter)

  val bloopCompatSettings: Seq[Def.Setting[_]] = List(
    Keys.reresolveSbtArtifacts := true
  )

  import sbt.Task
  def cloneTask[T](task: Task[T]): Task[T] = {
    task.copy(
      info = task.info.setName("randooooooooooom1"),
      work = {
        task.work match {
          case sbt.DependsOn(in, deps) => sbt.DependsOn(in, deps)
          case w: sbt.Mapped[t, k] => sbt.Mapped[t, k](w.in, w.f, w.alist)
          case w: sbt.FlatMapped[t, k] => sbt.FlatMapped[t, k](w.in, w.f, w.alist)
          case sbt.Join(in, f) => sbt.Join(in, f)
          case sbt.Pure(f, inline) => sbt.Pure(f, inline)
          case sbt.Selected(fab, fin) => sbt.Selected(fab, fin)
        }
      }
    )
  }
}
