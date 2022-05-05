package bloop.bloopgun

final case class BloopgunParams(
    bloopVersion: String,
    nailgunHelp: Boolean = false,
    verbose: Boolean = false,
    nailgunShowVersion: Boolean = false,
    args: List[String] = Nil,
    server: Boolean = false,
    serverConfig: ServerConfig = ServerConfig()
)
