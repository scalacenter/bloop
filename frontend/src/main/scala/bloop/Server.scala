package bloop

import java.net.InetAddress

import bloop.logging.BloopLogger
import bloop.logging.Logger
import bloop.logging.Slf4jAdapter
import bloop.util.ProxySetup

import java.io.InputStream
import java.io.PrintStream

import com.martiansoftware.nailgun.NGListeningAddress
import com.martiansoftware.nailgun.NGConstants
import com.martiansoftware.nailgun.{Alias, NGContext, NGServer}

import scala.util.Try

class Server
object Server {
  private val defaultPort: Int = 8212 // 8100 + 'p'
  def main(args: Array[String]): Unit = {
    run(instantiateServer(args))
  }

  private[bloop] def instantiateServer(args: Array[String]): NGServer = {
    val port = Try(args(0).toInt).getOrElse(Server.defaultPort)
    val addr = InetAddress.getLoopbackAddress
    val logger = BloopLogger.default("bloop-nailgun-main")
    launchServer(System.in, System.out, System.err, addr, port, logger)
  }

  private[bloop] def launchServer(
      in: InputStream,
      out: PrintStream,
      err: PrintStream,
      addr: InetAddress,
      port: Int,
      logger: Logger
  ): NGServer = {
    val javaLogger = new Slf4jAdapter(logger)
    val address = new NGListeningAddress(addr, port)
    val poolSize = NGServer.DEFAULT_SESSIONPOOLSIZE
    val heartbeatMs = NGConstants.HEARTBEAT_TIMEOUT_MILLIS.toInt
    val server = new NGServer(address, poolSize, heartbeatMs, in, out, err, javaLogger)
    registerAliases(server)
    ProxySetup.init()
    server
  }

  def nailMain(ngContext: NGContext): Unit = {
    val server = ngContext.getNGServer
    import java.util.concurrent.ForkJoinPool

    ForkJoinPool
      .commonPool()
      .submit(new Runnable {
        override def run(): Unit = {
          server.shutdown(false)
        }
      })

    ()
  }

  private def run(server: NGServer): Unit = {
    server.run()
  }

  private def registerAliases(server: NGServer): Unit = {
    val aliasManager = server.getAliasManager
    aliasManager.addAlias(new Alias("about", "Show bloop information.", classOf[Cli]))
    aliasManager.addAlias(new Alias("clean", "Clean project(s) in the build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("compile", "Compile project(s) in the build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("test", "Run project(s)' tests in the build.", classOf[Cli]))
    aliasManager.addAlias(
      new Alias("run", "Run a main entrypoint for project(s) in the build.", classOf[Cli])
    )
    aliasManager.addAlias(new Alias("bsp", "Spawn a build server protocol instance.", classOf[Cli]))
    aliasManager.addAlias(
      new Alias("console", "Run the console for project(s) in the build.", classOf[Cli])
    )
    aliasManager.addAlias(new Alias("projects", "Show projects in the build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("configure", "Configure the bloop server.", classOf[Cli]))
    aliasManager.addAlias(new Alias("help", "Show bloop help message.", classOf[Cli]))
    aliasManager.addAlias(
      new Alias(
        "exit",
        "Kill the bloop server.",
        classOf[Server]
      )
    )

    // Register the default entrypoint in case the user doesn't use the right alias
    server.setDefaultNailClass(classOf[Cli])
    // Disable nails by class name so that we prevent classloading incorrect aliases
    server.setAllowNailsByClassName(false)
  }
}
