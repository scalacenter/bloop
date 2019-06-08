package bloop.bsp.client

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.BuildServer
import com.google.gson.Gson
import java.io.InputStream
import java.io.PrintStream
import org.eclipse.lsp4j.jsonrpc.Launcher
import ch.epfl.scala.bsp4j.ScalaBuildServer
import java.util.concurrent.ExecutorService
import java.util.concurrent.{Future => JFuture}

class BspClient extends bsp4j.BuildClient {
  private final val gson = new Gson()

  def onConnectWithServer(server: BuildServer, listening: JFuture[_]): Unit = ???
  def onBuildLogMessage(params: bsp4j.LogMessageParams): Unit = ???
  def onBuildPublishDiagnostics(params: bsp4j.PublishDiagnosticsParams): Unit = ???
  def onBuildShowMessage(params: bsp4j.ShowMessageParams): Unit = ???
  def onBuildTargetDidChange(params: bsp4j.DidChangeBuildTarget): Unit = ???
  def onBuildTaskFinish(params: bsp4j.TaskFinishParams): Unit = ???
  def onBuildTaskProgress(params: bsp4j.TaskProgressParams): Unit = ???
  def onBuildTaskStart(params: bsp4j.TaskStartParams): Unit = ???
}

object BspClient {
  trait BloopBuildServer extends BuildServer with ScalaBuildServer
  def connectToServer(
      client: BspClient,
      clientIn: InputStream,
      clientOut: PrintStream,
      executor: ExecutorService
  ) = {
    val launcher = new Launcher.Builder[BloopBuildServer]()
    //.traceMessages(new PrintWriter(System.out))
      .setRemoteInterface(classOf[BloopBuildServer])
      .setInput(clientIn)
      .setOutput(clientOut)
      .setLocalService(client)
      .setExecutorService(executor)
      .create()

    val listening = launcher.startListening()
    val remoteServer = launcher.getRemoteProxy()
    client.onConnectWithServer(remoteServer, listening)
    ???
  }
}
