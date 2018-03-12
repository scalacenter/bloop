import sbt.{RootProject, uri}

object Integrations {

  val ApacheSpark = RootProject(
    uri("git://github.com/scalacenter/spark.git#4e037d614250b855915c28ac1e84471075293124"))
  val LihaoyiUtest = RootProject(
    uri("git://github.com/lihaoyi/utest.git#b5440d588d5b32c85f6e9392c63edd3d3fed3106"))
  val ScalaScala = RootProject(
    uri("git://github.com/scalacenter/scala.git#36bd1ad97c38b177a24a7762c74a9668de330f31"))
  val ScalaCenterVersions = RootProject(
    uri("git://github.com/scalacenter/versions.git#c296028a33b06ba3a41d399d77c21f6b7100c001"))

}
