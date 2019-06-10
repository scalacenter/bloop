package bloop.bsp.client

import java.util.concurrent.{Future => JFuture}
import com.google.gson.Gson
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.{util => ju}
import java.util.concurrent.CompletableFuture
import ch.epfl.scala.bsp4j.CompileResult
import java.nio.file.Path
import java.net.URI

class BspClientConnection(workspaceDir: Path, listening: JFuture[_], server: BloopBspServer) {
  private final val gson = new Gson()
  private final var listeningOpt: Option[JFuture[_]] = null

  def isActive: Boolean = !listening.isDone()

  def compile(btid: BuildTargetIdentifier): CompletableFuture[CompileResult] = {
    val params = new CompileParams(ju.Collections.singletonList(btid))
    server.buildTargetCompile(params)
  }
}

object BspClientConnection {
  def toBloopBuildTargetIdentifier(
      projectName: String,
      projectBaseDir: Path
  ): BuildTargetIdentifier = {
    val existingUri = projectBaseDir.toUri
    val uri = new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${projectName}",
      existingUri.getFragment
    )
    new BuildTargetIdentifier(uri.toString)
  }
}
