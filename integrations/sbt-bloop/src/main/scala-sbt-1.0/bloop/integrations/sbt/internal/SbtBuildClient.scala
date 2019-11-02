package bloop.integrations.sbt.internal

import bloop.bloop4j.api.NakedLowLevelBuildClient

import java.nio.file.Path
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import ch.epfl.scala.bsp4j.InitializeBuildResult
import java.util.concurrent.CompletableFuture
import bloop.bloop4j.api.handlers.BuildClientHandlers
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.InitializeBuildParams
import bloop.bloop4j.BloopExtraBuildParams
import com.google.gson.Gson

final class SbtBuildClient(
    baseDir: Path,
    val in: InputStream,
    val out: OutputStream,
    handlers: MultiProjectClientHandlers,
    ec: Option[ExecutorService]
) extends NakedLowLevelBuildClient[BuildClientHandlers](baseDir, in, out, handlers, None, ec) {
  def initializeAsSbtClient(
      sbtVersion: String
  ): CompletableFuture[InitializeBuildResult] = {
    import scala.collection.JavaConverters._
    val capabilities = new BuildClientCapabilities(List("scala", "java").asJava)
    val params = new InitializeBuildParams(
      "sbt",
      sbtVersion,
      "2.0.0-M4",
      baseDir.toUri.toString,
      capabilities
    )

    val gson = new Gson()
    val extraParams = new BloopExtraBuildParams()
    extraParams.setOwnsBuildFiles(true)
    params.setData(gson.toJsonTree(extraParams))

    initialize(params)
  }

}
