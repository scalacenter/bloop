package bloop.bloopgun

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
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
  private lazy val projectDirectories = ProjectDirectories.from("", "", "bloop")
  private lazy val bloopCacheDir: Path = Paths.get(projectDirectories.cacheDir)
  private lazy val bloopDataDir: Path = Paths.get(projectDirectories.dataDir)

  lazy val daemonDir: Path = {
    def defaultDir = {
      val baseDir =
        if (Properties.isMac) bloopCacheDir
        else bloopDataDir
      baseDir.resolve("daemon")
    }
    val dir = Option(System.getenv("BLOOP_DAEMON_DIR")).filter(_.trim.nonEmpty) match {
      case Some(dirStr) => Paths.get(dirStr)
      case None => defaultDir
    }
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      if (!Properties.isWin)
        Files.setPosixFilePermissions(
          dir,
          PosixFilePermissions.fromString("rwx------")
        )
    }
    dir
  }
  def daemonPipeName: String = "scala_bloop_server"
}
