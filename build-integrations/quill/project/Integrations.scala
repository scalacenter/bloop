import sbt.{RootProject, uri}

object Integrations {
  val Quill = RootProject(
    uri("https://github.com/getquill/quill.git#5ad49311e3490972489e66cc999eacb4eda37862")
  )
}
