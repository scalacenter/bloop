import sbt.{RootProject, uri}

object Integrations {
  val Scalajs1 = RootProject(
    uri("https://github.com/jvican/cross-platform-scalajs.git#0e776ae4b8ef5b9fae7f2374db01b227dc1ad9fc"))
  val Atlas = RootProject(
    uri("https://github.com/scalacenter/atlas.git#ab4251e17a2a02f2d5fe9c7ee83ebcde132c354c"))
}
