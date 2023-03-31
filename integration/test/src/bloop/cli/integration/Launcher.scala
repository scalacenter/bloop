package bloop.cli.integration

object Launcher {

  lazy val launcher = sys.props.getOrElse(
    "test.bloop-cli.path",
    sys.error("test.bloop-cli.path not set")
  )

}
