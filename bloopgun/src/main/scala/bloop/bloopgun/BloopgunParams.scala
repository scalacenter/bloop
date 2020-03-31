package bloop.bloopgun

final case class BloopgunParams(
    bloopVersion: String,
    nailgunServer: String = Defaults.Host,
    nailgunPort: Int = Defaults.Port,
    nailgunHelp: Boolean = false,
    verbose: Boolean = false,
    nailgunShowVersion: Boolean = false,
    args: List[String] = Nil,
    server: Boolean = false,
    serverConfig: ServerConfig = ServerConfig()
)
