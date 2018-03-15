package bloop.integrations.sbt

import java.io.File

object Compat {
  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)

  def generateCacheFile(s: sbt.Keys.TaskStreams, id: String) = s.cacheDirectory / id
}
