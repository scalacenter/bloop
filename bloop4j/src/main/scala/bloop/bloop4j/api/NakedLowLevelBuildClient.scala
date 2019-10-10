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

class NakedLowLevelBuildClient(
    clientName: String,
    clientVersion: String,
    clientIn: InputStream,
    clientOut: OutputStream,
    handlers: BuildClientHandlers,
    underlyingProcess: Option[Process]
) extends LowLevelBuildClientApi[CompletableFuture] {
  val testDirectory = Files.createTempDirectory("remote-bloop-client")

  private var server: ScalaBuildServerBridge = null
  def initialize: CompletableFuture[InitializeBuildResult] = {
    server = unsafeConnectToBuildServer(handlers, testDirectory)

    import scala.collection.JavaConverters._
    val capabilities = new BuildClientCapabilities(List("scala", "java").asJava)
    val initializeParams = new InitializeBuildParams(
      clientName,
      clientVersion,
      "2.0.0-M4",
      testDirectory.toUri.toString,
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

  trait ScalaBuildServerBridge extends BuildServer with ScalaBuildServer

  private def unsafeConnectToBuildServer(
      localClient: BuildClient,
      baseDir: Path
  ): ScalaBuildServerBridge = {
    val launcher = new Launcher.Builder[ScalaBuildServerBridge]()
    //.traceMessages(new PrintWriter(System.out))
      .setRemoteInterface(classOf[ScalaBuildServerBridge])
      .setInput(clientIn)
      .setOutput(clientOut)
      .setLocalService(localClient)
      .create()

    launcher.startListening()
    val serverBridge = launcher.getRemoteProxy
    localClient.onConnectWithServer(serverBridge)
    serverBridge
  }
}

object RemoteBloopClient {
  def fromLauncherJars(
      clientName: String,
      clientVersion: String,
      buildDir: Path,
      launcherJars: Seq[Path],
      handlers: BuildClientHandlers,
      additionalEnv: ju.Map[String, String] = new ju.HashMap()
  ): NakedLowLevelBuildClient = {
    import scala.collection.JavaConverters._
    val builder = new ProcessBuilder()
    val newEnv = builder.environment()
    newEnv.putAll(additionalEnv)

    builder.redirectInput(Redirect.PIPE)
    builder.redirectOutput(Redirect.PIPE)

    val delimiter = if (Environment.isWindows) ";" else ":"
    val stringClasspath = launcherJars.map(_.normalize().toAbsolutePath).mkString(delimiter)
    val cmd = List("java", "-classpath", stringClasspath, "bloop.launcher.Launcher")
    val process = builder.command(cmd.asJava).directory(buildDir.toFile).start()
    new NakedLowLevelBuildClient(
      clientName,
      clientVersion,
      process.getInputStream(),
      process.getOutputStream(),
      handlers,
      Some(process)
    )
  }
}
