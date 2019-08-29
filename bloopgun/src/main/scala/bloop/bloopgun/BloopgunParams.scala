package bloop.bloopgun

final case class BloopgunParams(
    nailgunServer: String = Defaults.Host,
    nailgunPort: Int = Defaults.Port,
    help: Boolean = false,
    nailgunHelp: Boolean = false,
    verbose: Boolean = false,
    nailgunShowVersion: Boolean = false,
    args: List[String] = Nil
)
