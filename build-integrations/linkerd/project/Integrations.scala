import sbt.{RootProject, uri}

object Integrations {
  val Linkerd = RootProject(
    uri("https://github.com/jvican/linkerd.git#351032288eba1cef2ead16822361a1b944e14513")
  )
}
