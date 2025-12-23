package bloop.integrations.sbt

import sbt.File
import sbt.Keys
import sbt.Def
import sbt.Task
import sbt.internal.PluginDiscovery

/** sbt 2.x specific compatibility */
trait CompatPlatform {
  // In sbt 2.0, Result is an enum with Inc and Value as enum cases
  type Result[T] = sbt.Result[T]
  val Result = sbt.Result
  val Inc = sbt.Result.Inc
  val Value = sbt.Result.Value
  type ScopeSettings = sbt.Settings

  // In sbt 2.0, IntegrationTest is not available by default, we create it
  val IntegrationTest: sbt.Configuration = sbt.Configuration.of("IntegrationTest", "it")

  // In sbt 2.0, classpath uses HashedVirtualFileRef, need conversion
  implicit class ClasspathOps(val classpath: Seq[sbt.Attributed[xsbti.HashedVirtualFileRef]]) {
    def toFiles(implicit fileConverter: xsbti.FileConverter): Seq[sbt.Attributed[File]] = {
      classpath.map { attributed =>
        val file = fileConverter.toPath(attributed.data).toFile
        // In sbt 2.0, we preserve the original Attributed metadata and map the data
        sbt.Attributed(file)(attributed.metadata)
      }
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
    Keys.discoveredSbtPlugins := Def
      .uncached(Def.taskDyn {
        val roots = Keys.executionRoots.value
        if (!Keys.sbtPlugin.value) inlinedTask(PluginDiscovery.emptyDiscoveredNames)
        else {
          if (roots.exists(scoped => scoped.key == BloopKeys.bloopInstall.key)) {
            inlinedTask(PluginDiscovery.emptyDiscoveredNames)
          } else {
            Def.task(PluginDiscovery.discoverSourceAll(Keys.compile.value))
          }
        }
      })
      .value
  )

  def taskDefFromTask[T](task: sbt.Task[T]) = task.attributes.get(Keys.taskDefinitionKey)
}
