package bloop.test

import scala.tools.nsc.{ Global, Phase }
import scala.tools.nsc.plugins.{ Plugin, PluginComponent }

class BloopTestPlugin(val global: Global) extends Plugin {
  val name = "bloop-test-plugin"
  val description = "Just prints whenever it's initialized"
  val components = List[PluginComponent](BloopTestComponent)
  global.reporter.echo(
    global.NoPosition,
    s"Bloop test plugin classloader: ${this.getClass.getClassLoader}"
  )

  private object BloopTestComponent extends PluginComponent {
    val global = BloopTestPlugin.this.global
    import global._
    override val runsAfter = List("erasure")
    val phaseName = "BloopTest"

    override def newPhase(prev: Phase): StdPhase = new StdPhase(prev) {
      override def apply(unit: CompilationUnit): Unit = ()
    }
  }
}