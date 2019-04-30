import sbt.{RootProject, uri}

object Integrations {
  val Circe = RootProject(
    uri("https://github.com/circe/circe.git#5349535ca0631678a76939daa695c8b05db70ac5")
  )
}
