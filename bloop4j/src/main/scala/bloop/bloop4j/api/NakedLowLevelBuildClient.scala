package bloop.bloop4j.api

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.{util => ju}

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.CleanCacheParams
import ch.epfl.scala.bsp4j.CleanCacheResult
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.ScalaBuildServer

import bloop.bloop4j.BloopStopClientCachingParams
import bloop.bloop4j.api.handlers.BuildClientHandlers
import bloop.bloop4j.util.Environment

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

class NakedLowLevelBuildClient[ClientHandlers <: BuildClientHandlers](
    clientBaseDir: Path,
    clientIn: InputStream,
    clientOut: OutputStream,
    handlers: ClientHandlers,
    executor: Option[ExecutorService]
) extends LowLevelBuildClientApi[CompletableFuture] {
  private var server: BloopBuildServer = null

  def initialize(params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] = {
    server = unsafeConnectToBuildServer(handlers)
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

  def cleanCache(params: CleanCacheParams): CompletableFuture[CleanCacheResult] = {
    server.buildTargetCleanCache(params)
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
      localClient: BuildClient
  ): BloopBuildServer = {
    val bloopClientDir = Files.createDirectories(clientBaseDir.resolve(".bloop"))
    val offloadingFile = bloopClientDir.resolve("sbt-bsp.log")
    val shouldTraceMessages = Files.exists(offloadingFile)
    val ps = new PrintWriter(Files.newOutputStream(offloadingFile))
    val builder = new Launcher.Builder[BloopBuildServer]()
      .setRemoteInterface(classOf[BloopBuildServer])
      .setInput(clientIn)
      .setOutput(clientOut)
      .setLocalService(localClient)
    if (shouldTraceMessages) builder.traceMessages(ps)
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
      executor
    )
  }
}
