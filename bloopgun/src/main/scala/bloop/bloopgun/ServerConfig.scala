package bloop.bloopgun

final case class ServerConfig(
    host: Option[String] = None,
    port: Option[Int] = None,
    startTimeout: Option[Int] = None
) {
  def userOrDefaultHost: String = host.getOrElse(Defaults.Host)
  def userOrDefaultPort: Int = port.getOrElse(Defaults.Port)
}
