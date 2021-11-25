package bloop.bloopgun
import java.nio.file.Path
import libdaemonjvm.LockFiles

final case class ServerConfig(
    listenOn: Either[(Option[String], Option[Int]), Option[Path]] = Right(None),
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
      case Right(daemonDirOpt) =>
        val daemonDir = daemonDirOpt.getOrElse(Defaults.daemonDir)
        Right(LockFiles.under(daemonDir, "scala_bloop_server"))
    }
  override def toString(): String = listenOnWithDefaults match {
    case Left((host, port)) => s"$host:$port"
    case Right(daemonDir) => daemonDir.toString
  }
}
