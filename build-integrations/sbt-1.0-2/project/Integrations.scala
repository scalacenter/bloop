import sbt.{RootProject, uri}

object Integrations {
  val Scalajs1 = RootProject(
    uri("git://github.com/jvican/cross-platform-scalajs.git#0e776ae4b8ef5b9fae7f2374db01b227dc1ad9fc"))
}
