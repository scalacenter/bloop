package bloop.bloopgun

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

import scala.util.Properties

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

  private lazy val (bloopCacheDir, bloopDataDir) = {
    import bloop.bloopgun.internal.ProjDirHelper
    (Paths.get(ProjDirHelper.cacheDir()), Paths.get(ProjDirHelper.dataDir()))
  }

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
