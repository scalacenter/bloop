package bloop.integrations.sbt

import sbt.{File, Inc => SbtInc, IntegrationTest => SbtIntegrationTest, Value => SbtValue}
import sbt.Keys
import sbt.Def
import sbt.Scope
import sbt.Task
import sbt.internal.PluginDiscovery

/** sbt 1.x specific compatibility */
trait CompatPlatform {
  // In sbt 1.x, Result is a simple type with Inc and Value as objects
  type Result[T] = sbt.Result[T]
  val Result = sbt.Result
  val Inc = SbtInc
  val Value = SbtValue
  type ScopeSettings = sbt.Settings[Scope]

  // IntegrationTest is directly available in sbt 1.x
  val IntegrationTest = SbtIntegrationTest

  // In sbt 1.x, classpath uses File directly, no conversion needed
  implicit class ClasspathOps(val classpath: Seq[sbt.Attributed[File]]) {
    def toFiles: Seq[sbt.Attributed[File]] = classpath
  }

  object TaskExtraSyntax {
    implicit class TaskOps[T](tasks: Seq[sbt.Task[T]]) {
      def join: sbt.Task[Seq[T]] = sbt.std.TaskExtra.joinTasks(tasks).join
    }
  }

  def inlinedTask[T](value: T): Def.Initialize[sbt.Task[T]] = Def.toITask(Def.value(value))

  /**
   * Replace the implementation of discovered sbt plugins so that we don't run it
   * when we `bloopGenerate` or `bloopInstall`. This is important because when there
   * are sbt plugins in the build they trigger the compilation of all the modules.
   * We do no-op when there is indeed an sbt plugin in the build.
   */
  def discoveredSbtPluginsSettings: Seq[Def.Setting[?]] = List(
    Keys.discoveredSbtPlugins := Def.taskDyn {
      val roots = Keys.executionRoots.value
      if (!Keys.sbtPlugin.value) inlinedTask(PluginDiscovery.emptyDiscoveredNames)
      else {
        if (roots.exists(scoped => scoped.key == BloopKeys.bloopInstall.key)) {
          inlinedTask(PluginDiscovery.emptyDiscoveredNames)
        } else {
          Def.task(PluginDiscovery.discoverSourceAll(Keys.compile.value))
        }
      }
    }.value
  )

  def taskDefFromTask[T](task: sbt.Task[T]): Option[Def.ScopedKey[_]] =
    task.info.get(Keys.taskDefinitionKey)
}
