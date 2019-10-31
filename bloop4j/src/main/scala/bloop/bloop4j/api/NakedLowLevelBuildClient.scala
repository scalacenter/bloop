package bloop.bloop4j.api

import bloop.config.Config
import bloop.bloop4j.util.Environment
import bloop.bloop4j.api.handlers.BuildClientHandlers

import java.net.URI
import java.{util => ju}
import java.nio.file.{Files, Path}
import java.io.InputStream
import java.io.OutputStream
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.CompletableFuture
import java.io.PrintWriter
import java.util.concurrent.ExecutorService

import org.eclipse.lsp4j.jsonrpc.Launcher

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.ScalaBuildServer
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import java.nio.file.Paths
import java.io.PrintStream
import bloop.bloop4j.BloopStopClientCachingParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

class NakedLowLevelBuildClient[ClientHandlers <: BuildClientHandlers](
    clientBaseDir: Path,
    clientIn: InputStream,
    clientOut: OutputStream,
    handlers: ClientHandlers,
    underlyingProcess: Option[Process],
    executor: Option[ExecutorService]
) extends LowLevelBuildClientApi[CompletableFuture] {
  private var server: BloopBuildServer = null

  def initialize(params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] = {
    server = unsafeConnectToBuildServer(handlers, clientBaseDir)
    server.buildInitialize(params).thenApply { result =>
      server.onBuildInitialized()
      result
    }
  }

  def stopClientCaching(params: BloopStopClientCachingParams): Unit = {
    server.bloopStopClientCaching(params)
  }

  def compile(params: CompileParams): CompletableFuture[CompileResult] = {
    server.buildTargetCompile(params)
  }

  def exit: CompletableFuture[Unit] = {
    server.buildShutdown().thenApply { _ =>
      try server.onBuildExit()
      finally ()
    }
  }

  def getHandlers: ClientHandlers = handlers

  trait BloopBuildServer extends BuildServer with ScalaBuildServer {
    @JsonNotification("bloop/stopClientCaching")
    def bloopStopClientCaching(params: BloopStopClientCachingParams): Unit
  }

  val cwd = sys.props("user.dir")
  private def unsafeConnectToBuildServer(
      localClient: BuildClient,
      baseDir: Path
  ): BloopBuildServer = {
    val bloopClientDir = Files.createDirectories(clientBaseDir.resolve(".bloop"))
    val offloadingFile = bloopClientDir.resolve("sbt-bsp.log")
    val ps = new PrintWriter(Files.newOutputStream(offloadingFile))
    val builder = new Launcher.Builder[BloopBuildServer]()
      .traceMessages(ps)
      .setRemoteInterface(classOf[BloopBuildServer])
      .setInput(clientIn)
      .setOutput(clientOut)
      .setLocalService(localClient)
    executor.foreach(executor => builder.setExecutorService(executor))
    val launcher = builder.create()

    launcher.startListening()
    val serverBridge = launcher.getRemoteProxy
    localClient.onConnectWithServer(serverBridge)
    serverBridge
  }
}

object NakedLowLevelBuildClient {
  def fromLauncherJars[ClientHandlers <: BuildClientHandlers](
      clientBaseDir: Path,
      launcherJars: Seq[Path],
      handlers: ClientHandlers,
      executor: Option[ExecutorService],
      additionalEnv: ju.Map[String, String] = new ju.HashMap()
  ): NakedLowLevelBuildClient[ClientHandlers] = {
    import scala.collection.JavaConverters._
    val builder = new ProcessBuilder()
    val newEnv = builder.environment()
    newEnv.putAll(additionalEnv)

    builder.redirectInput(Redirect.PIPE)
    builder.redirectOutput(Redirect.PIPE)

    val delimiter = if (Environment.isWindows) ";" else ":"
    val stringClasspath = launcherJars.map(_.normalize().toAbsolutePath).mkString(delimiter)
    val cmd = List("java", "-classpath", stringClasspath, "bloop.launcher.Launcher")
    val process = builder.command(cmd.asJava).directory(clientBaseDir.toFile).start()
    new NakedLowLevelBuildClient(
      clientBaseDir,
      process.getInputStream(),
      process.getOutputStream(),
      handlers,
      Some(process),
      executor
    )
  }
}
