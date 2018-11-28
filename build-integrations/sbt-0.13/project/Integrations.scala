import sbt.{RootProject, uri}

object Integrations {

  val ApacheSpark = RootProject(
    uri("https://github.com/scalacenter/spark.git#e626d416451534d9a817cc6a8efa4b898efc97ea"))
  val LihaoyiUtest = RootProject(
    uri("https://github.com/lihaoyi/utest.git#b5440d588d5b32c85f6e9392c63edd3d3fed3106"))
  val ScalaScala = RootProject(
    uri("https://github.com/scalacenter/scala.git#dc36e73a10ca2835489c878b5068c6cc64c7d6b6"))
  val ScalaCenterVersions = RootProject(
    uri("https://github.com/scalacenter/versions.git#c296028a33b06ba3a41d399d77c21f6b7100c001"))

}
