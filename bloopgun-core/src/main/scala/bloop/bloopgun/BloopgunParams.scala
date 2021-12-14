package bloop.bloopgun

import java.nio.file.Path

final case class BloopgunParams(
    bloopVersion: String,
    nailgunHelp: Boolean = false,
    verbose: Boolean = false,
    nailgunShowVersion: Boolean = false,
    args: List[String] = Nil,
    server: Boolean = false,
    serverConfig: ServerConfig = ServerConfig()
)
