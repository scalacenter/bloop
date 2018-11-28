import sbt.{RootProject, uri}

object Integrations {

  val Scalding = RootProject(
    uri("https://github.com/jvican/scalding.git#501a787103ee5ae3371fc51d007145fe00342017"))
  val SummingBird = RootProject(
    uri("https://github.com/twitter/summingbird.git#d5322b3f73a2ceaffe6b06edf18142bb20815763"))

}
