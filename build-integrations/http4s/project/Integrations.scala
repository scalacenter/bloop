import sbt.{RootProject, uri}

object Integrations {
  val Http4s = RootProject(
    uri(
      "https://github.com/http4s/http4s.git#d54c8a427313a337e21753caf57f6109fdaa37f0"
    )
  )
}
