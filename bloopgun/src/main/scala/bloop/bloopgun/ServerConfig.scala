package bloop.bloopgun
import java.nio.file.Path

final case class ServerConfig(
    host: Option[String] = None,
    port: Option[Int] = None,
    serverArgs: List[String] = Nil,
    serverLocation: Option[Path] = None,
    startTimeout: Option[Int] = None,
    fireAndForget: Boolean = false
) {
  def userOrDefaultHost: String = host.getOrElse(Defaults.Host)
  def userOrDefaultPort: Int = port.getOrElse(Defaults.Port)
  override def toString(): String = userOrDefaultHost + ":" + userOrDefaultPort
}
