package bloop.cli.integration

object Launcher {

  lazy val launcher = sys.props.getOrElse(
    "test.bloop-cli.path",
    sys.error("test.bloop-cli.path not set")
  )

  lazy val repoRoot = os.Path(sys.props.getOrElse(
    "test.bloop-cli.repo",
    sys.error("test.bloop-cli.repo not set")
  ))

  lazy val extraEnv =
    Map(
      "COURSIER_REPOSITORIES" -> s"ivy:${repoRoot.toNIO.toUri.toASCIIString}/[defaultPattern]|ivy2Local|central"
    )

}
