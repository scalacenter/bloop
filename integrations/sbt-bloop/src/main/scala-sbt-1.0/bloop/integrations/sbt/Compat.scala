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
      else {
        Def.taskDyn {
          val tryBloopConnection = shouldTryToConnectToServer.value
          val hasConnection: Boolean = ???
          if (hasConnection) bloopCompile
          else Defaults.compileTask
        }
      }
    }.value
  )

  private final var lastExecutedCommands: Seq[Exec] = Vector.empty

  /**
   * Returns yes if the caller should attempt a bloop connection, no otherwise.
   *
   * We don't want to try to connect to the server on every compile invocation
   * in the build graph, so this task is responsible for finding a way minimize
   * the amount of such system process calls.
   *
   * Following the implementation details below, we will only try to attempt a
   * build connection once per command that is executed. That is, whenever the
   * history is no longer the same (using referential equality).
   */
  final lazy val shouldTryToConnectToServer: Def.Initialize[Task[Boolean]] = {
    Def.task {
      val state = Keys.state.value
      val executedCommands = state.history.executed
      lastExecutedCommands.synchronized {
        val tryConnection = lastExecutedCommands == executedCommands
        // Whenever we try the connection, we update the reference
        if (tryConnection) lastExecutedCommands = executedCommands
        tryConnection
      }
    }
  }

  //final lazy val shouldTryToConnectToServer: Def.Initialize[Task[Boolean]] = {

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

  /**
   * The instrumented bloop compile is responsible of off-loading compilation to bloop.
   *
   * The task calls transitively `bloopGenerate` to guarantee all dependencies of
   * this project have a bloop project configuration file and then goes on
   * invoking `bloopGenerate` on its own and asking bloop to compile via BSP.
   *
   * We can only run this code if there is a
   */
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
