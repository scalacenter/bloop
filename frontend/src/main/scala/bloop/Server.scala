package bloop

import java.net.InetAddress

import com.martiansoftware.nailgun.{Alias, NGContext, NGServer}

import scala.util.Try

class Server
object Server {
  private val defaultPort: Int = 2113
  def main(args: Array[String]): Unit = {
    val port = Try(args(0).toInt).getOrElse(Server.defaultPort)
    val addr = InetAddress.getLoopbackAddress
    val server = new NGServer(addr, port)
    registerAliases(server)
    run(server)
  }

  def nailMain(ngContext: NGContext): Unit = {
    val server = ngContext.getNGServer
    shutDown(server)
  }

  private def run(server: NGServer): Unit = {
    server.run()
  }

  private def registerAliases(server: NGServer): Unit = {
    val aliasManager = server.getAliasManager
    aliasManager.addAlias(new Alias("about", "Show bloop information.", classOf[Cli]))
    aliasManager.addAlias(new Alias("clean", "Clean project(s) in this build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("compile", "Compile project(s) in this build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("help", "Show bloop help message.", classOf[Cli]))
    aliasManager.addAlias(new Alias("projects", "Show projects in this build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("test", "Run project(s)' tests in this build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("exit", "Kill the bloop server.", classOf[Server]))
  }

  private def shutDown(server: NGServer): Unit = {
    import bloop.engine.State
    import bloop.engine.tasks.Tasks
    State.stateCache.allStates.foreach(s => Tasks.persist(s))
    server.shutdown(true)
  }
}
