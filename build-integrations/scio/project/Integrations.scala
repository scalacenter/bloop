import sbt.{RootProject, uri}

object Integrations {
  val Scio = RootProject(
    uri("https://github.com/jvican/scio.git#da2e33cdd3fca0ebb1302f83ff772748290b6e0b")
  )
}
