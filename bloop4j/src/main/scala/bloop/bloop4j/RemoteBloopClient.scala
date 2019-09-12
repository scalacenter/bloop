package bloop.bloop4j

import bloop.config.Config

import java.net.URI
import java.{util => ju}
import java.nio.file.{Files, Path}
import java.io.InputStream
import java.io.PrintStream
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

class RemoteBloopClient(clientIn: InputStream, clientOut: PrintStream) {
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
      server.onBuildExit()
      ()
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
  def unsafeCreateBuildTarget(bloopConfigPath: Path): BuildTargetIdentifier = {
    bloop.config.read(bloopConfigPath) match {
      case Left(value) => throw value
      case Right(configFile) =>
        val projectUri = deriveProjectUri(bloopConfigPath, configFile.project.name)
        new BuildTargetIdentifier(projectUri.toString)
    }
  }

  def unsafeCreateBuildTarget(
      bloopConfigDir: Path,
      configFile: Config.File
  ): BuildTargetIdentifier = {
    val projectName = configFile.project.name
    val configPath = bloopConfigDir.resolve(s"$projectName.json")
    val projectUri = deriveProjectUri(configPath, projectName)
    bloop.config.write(configFile, configPath)
    new BuildTargetIdentifier(projectUri.toString)
  }

  def deriveProjectUri(projectBaseDir: Path, name: String): URI = {
    // This is the "idiomatic" way of adding a query to a URI in Java
    val existingUri = projectBaseDir.toUri
    new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${name}",
      existingUri.getFragment
    )
  }

  case class ProjectId(name: String, dir: Path)
  def projectNameFrom(btid: BuildTargetIdentifier): ProjectId = {
    val existingUri = new URI(btid.getUri)
    val uriWithNoQuery = new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      null,
      existingUri.getFragment
    )

    val name = existingUri.getQuery().stripPrefix("id=")
    val projectDir = java.nio.file.Paths.get(uriWithNoQuery)
    ProjectId(name, projectDir)
  }
}
