package bloop.bloopgun

import java.nio.file.{Path, Paths}
import scala.util.Properties
import dev.dirs.ProjectDirectories

object Defaults {
  val Version = "0.9.3"
  val Host = "127.0.0.1"
  val Port = 8212

  val env: Map[String, String] = {
    import scala.collection.JavaConverters._
    System.getenv().asScala.toMap
  }

  object Time {
    val DefaultHeartbeatIntervalMillis = 500.toLong
    val SendThreadWaitTerminationMillis = 5000.toLong
  }

  // also more or less in bloop.io.Paths…
  private val projectDirectories = ProjectDirectories.from("", "", "bloop")
  private final val bloopCacheDir: Path = Paths.get(projectDirectories.cacheDir)
  private final val bloopDataDir: Path = Paths.get(projectDirectories.dataDir)

  final val daemonDir: Path = {
    val baseDir =
      if (Properties.isMac) bloopCacheDir
      else bloopDataDir
    baseDir.resolve("daemon")
  }
  final val daemonPipeName: String = "scala_bloop_server"
}
