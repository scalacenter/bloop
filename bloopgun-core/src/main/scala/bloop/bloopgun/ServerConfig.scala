package bloop.bloopgun
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Properties
import libdaemonjvm.LockFiles

final case class ServerConfig(
    listenOn: Either[(Option[String], Option[Int]), (Option[Path], Option[String])] = Right(
      (None, None)
    ),
    serverArgs: List[String] = Nil,
    serverLocation: Option[Path] = None,
    startTimeout: Option[Int] = None,
    fireAndForget: Boolean = false
) {
  def listenOnWithDefaults: Either[(String, Int), LockFiles] =
    listenOn match {
      case Left((hostOpt, portOpt)) =>
        val host = hostOpt.getOrElse(Defaults.Host)
        val port = portOpt.getOrElse(Defaults.Port)
        Left((host, port))
      case Right((daemonDirOpt, pipeNameOpt)) =>
        val daemonDir = daemonDirOpt.getOrElse(Defaults.daemonDir)
        val pipeName = pipeNameOpt.getOrElse(Defaults.daemonPipeName)
        ServerConfig.ensureSafeDirectoryExists(daemonDir)
        Right(LockFiles.under(daemonDir, pipeName))
    }
  override def toString(): String = listenOnWithDefaults match {
    case Left((host, port)) => s"$host:$port"
    case Right(daemonDir) => daemonDir.toString
  }
}

object ServerConfig {
  private def ensureSafeDirectoryExists(dir: Path): Unit =
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      if (!Properties.isWin)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }
}
