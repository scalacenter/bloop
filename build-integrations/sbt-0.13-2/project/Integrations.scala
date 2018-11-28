import sbt.{RootProject, uri}

object Integrations {

  val Lichess = RootProject(
    uri("https://github.com/scalacenter/lila.git#34fbc815d49db5d24c5a5ba6e3538168a2cb5b17"))

}
