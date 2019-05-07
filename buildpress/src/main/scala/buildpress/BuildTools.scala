package buildpress

import buildpress.io.AbsolutePath

sealed trait BuildTool
object BuildTool {
  final case class Sbt(baseDir: AbsolutePath, version: String) extends BuildTool {
    override def toString: String = s"sbt $version at $baseDir"
  }

  final case class Gradle(baseDir: AbsolutePath, version: String) extends BuildTool {
    override def toString: String = s"gradle $version at $baseDir"
  }
}
