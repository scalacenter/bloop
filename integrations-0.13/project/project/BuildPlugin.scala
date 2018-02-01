package build

import sbt._

object BuildPlugin {

  val ApacheSpark = RootProject(
    uri("git://github.com/scalacenter/spark.git#4e037d614250b855915c28ac1e84471075293124"))
  val LihaoyiUtest = RootProject(
    uri("git://github.com/lihaoyi/utest.git#b5440d588d5b32c85f6e9392c63edd3d3fed3106"))
  val ScalaScala = RootProject(
    uri("git://github.com/scalacenter/scala.git#6809ff601ed2efc81d3d0a199592f178429bf2ec"))
  val ScalaCenterVersions = RootProject(
    uri("git://github.com/scalacenter/versions.git#c296028a33b06ba3a41d399d77c21f6b7100c001"))

  def writeAddSbtPlugin(buildRoot: File): Unit = {
    val version = sys.env
      .get("bloop_version")
      .getOrElse(sys.error("Missing environment variable: bloop_version"))
    val content = s"""addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "$version")"""

    IO.write(buildRoot / "project" / "bloop-test-settings.sbt", content)
  }

}
