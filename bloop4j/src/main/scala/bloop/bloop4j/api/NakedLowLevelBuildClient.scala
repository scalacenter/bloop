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

class NakedLowLevelBuildClient[ClientHandlers <: BuildClientHandlers](
    clientName: String,
    clientVersion: String,
    clientBaseDir: Path,
    clientIn: InputStream,
    clientOut: OutputStream,
    handlers: ClientHandlers,
    underlyingProcess: Option[Process],
    executor: Option[ExecutorService]
) extends LowLevelBuildClientApi[CompletableFuture] {
  private var server: ScalaBuildServerBridge = null
  def initialize: CompletableFuture[InitializeBuildResult] = {
    server = unsafeConnectToBuildServer(handlers, clientBaseDir)

    import scala.collection.JavaConverters._
    val capabilities = new BuildClientCapabilities(List("scala", "java").asJava)
    val initializeParams = new InitializeBuildParams(
      clientName,
      clientVersion,
      "2.0.0-M4",
      clientBaseDir.toUri.toString,
      capabilities
    )

    server.buildInitialize(initializeParams).thenApply { result =>
      server.onBuildInitialized()
      result
    }
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

  trait ScalaBuildServerBridge extends BuildServer with ScalaBuildServer

  val cwd = sys.props("user.dir")
  private def unsafeConnectToBuildServer(
      localClient: BuildClient,
      baseDir: Path
  ): ScalaBuildServerBridge = {
    val offloadingFile = Paths.get(cwd).resolve("bloop-offloading.logs")
    if (!Files.exists(offloadingFile)) Files.createFile(offloadingFile)
    val ps = new PrintWriter(Files.newOutputStream(offloadingFile))
    val builder = new Launcher.Builder[ScalaBuildServerBridge]()
      .traceMessages(ps)
      .setRemoteInterface(classOf[ScalaBuildServerBridge])
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
      clientName: String,
      clientVersion: String,
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
      clientName,
      clientVersion,
      clientBaseDir,
      process.getInputStream(),
      process.getOutputStream(),
      handlers,
      Some(process),
      None //executor
    )
  }
}
