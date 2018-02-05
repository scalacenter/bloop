package build

import sbt._

object BuildPlugin {

  def writeAddSbtPlugin(buildRoot: File): Unit = {
    val version = sys.env
      .get("bloop_version")
      .getOrElse(sys.error("Missing environment variable: bloop_version"))
    val content = s"""addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "$version")"""

    IO.write(buildRoot / "project" / "bloop-test-settings.sbt", content)
  }

}
