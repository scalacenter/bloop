import sbt.{RootProject, uri}

object Integrations {
  val Scio = RootProject(
    uri("https://github.com/spotify/scio.git#825267ca5a891e69a822d3b6e77b1ee8f92a31f4")
  )
}
