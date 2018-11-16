package bloop

object Docs {
  def main(args: Array[String]): Unit = {
    val settings = mdoc.MainSettings()
      .withSiteVariables(Map("VERSION" -> bloop.internal.build.BuildInfo.version))
      .withArgs(args.toList)

    val exitCode = mdoc.Main.process(settings)
    if (exitCode != 0) sys.exit(exitCode)
  }
}