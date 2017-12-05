package bloop

import java.net.InetAddress

import bloop.engine.{ExecutionContext, FixedThreadPool}
import bloop.logging.Logger
import com.martiansoftware.nailgun.{Alias, NGContext, NGServer}

import scala.util.Try

class Server
object Server {
  private val defaultPort: Int = 2113
  private[bloop] val executionContext: ExecutionContext = new FixedThreadPool()

  def main(args: Array[String]): Unit = {
    val port = Try { args(0).toInt }.getOrElse(Server.defaultPort)
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
    aliasManager.addAlias(new Alias("about", "Show information about bloop", classOf[Cli]))
    aliasManager.addAlias(new Alias("clean", "Clean projects", classOf[Cli]))
    aliasManager.addAlias(new Alias("compile", "Compile projects", classOf[Cli]))
    aliasManager.addAlias(new Alias("help", "Show help message", classOf[Cli]))
    aliasManager.addAlias(new Alias("projects", "Shows the loaded projects", classOf[Cli]))
    aliasManager.addAlias(new Alias("test", "Runs the tests", classOf[Cli]))
    aliasManager.addAlias(new Alias("exit", "Exits bloop", classOf[Server]))
  }

  private def shutDown(server: NGServer): Unit = {
    val logger = Logger.get
    Project.persistAllProjects(logger)
    executionContext.shutdown()
    server.shutdown(true)
  }

}
