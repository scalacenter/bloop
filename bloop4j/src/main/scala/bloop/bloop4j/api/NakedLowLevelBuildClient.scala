package bloop.bloop4j.api

import bloop.config.Config
import bloop.bloop4j.util.Environment
import bloop.bloop4j.api.internal.TestBuildClient

import java.net.URI
import java.{util => ju}
import java.nio.file.{Files, Path}
import java.io.InputStream
import java.io.OutputStream
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
import java.lang.ProcessBuilder.Redirect

class NakedLowLevelBuildClient(
    clientIn: InputStream,
    clientOut: OutputStream,
    underlyingProcess: Option[Process]
) extends LowLevelBuildClientApi[CompletableFuture] {
  val testDirectory = Files.createTempDirectory("remote-bloop-client")

  private var client: TestBuildClient = null
  private var server: ScalaBuildServerBridge = null

  def initialize: CompletableFuture[InitializeBuildResult] = {
    client = new TestBuildClient
    server = unsafeConnectToBuildServer(client, testDirectory)

    import scala.collection.JavaConverters._
    val capabilities = new BuildClientCapabilities(List("scala", "java").asJava)
    val initializeParams = new InitializeBuildParams(
      "test-client",
      "1.0.0",
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
      buildDir: Path,
      launcherJars: Seq[Path],
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
    new NakedLowLevelBuildClient(process.getInputStream(), process.getOutputStream(), Some(process))
  }
}
