import sbt.{RootProject, uri}

object Integrations {
  val GuardianGrid = RootProject(
    uri("https://github.com/jvican/grid.git#a6b7d12d51092f99f6d33969172f5a1f67507c87"))
}
