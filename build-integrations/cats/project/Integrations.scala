import sbt.{RootProject, uri}

object Integrations {
  val Cats = RootProject(
    uri("https://github.com/cats/cats.git#e306a5011d580d35ed7c34246dae297d874f4fa7")
  )
}
