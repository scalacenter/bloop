package bloop.launcher.core

import java.nio.file.Path

object Feedback {
  val SkippingFullInstallation: String =
    "Python is missing from the classpath, skipping full bloop installation..."
  val UseFallbackInstallation: String =
    "The launcher will now try to resolve bloop and spawn an embedded build server..."
  val NoBloopVersion: String =
    "The bloop launcher accepts only one argument: the bloop version."

  def installationLogs(bloopDirectory: Path): String = {
    val bloopDir = bloopDirectory.toAbsolutePath.toString
    s"""
       |The launcher has installed `bloop` in $bloopDir
       |-> Recommendation: Add bloop to your PATH with `export PATH=$$PATH:$bloopDir <-
     """.stripMargin
  }

  def installingBloop(version: String): String =
    s"Bloop is not available in the machine, installing bloop ${version}..."

  def downloadingInstallerAt(url: java.net.URL): String = s"Downloading installer at ${url}..."
  def failedToDownloadInstallerAt(url: java.net.URL): String = {
    s"Failed to download installer at ${url}..."
  }

  def startingBloopServer(cmd: List[String]): String = {
    s"Starting the bloop server with '${cmd.mkString(" ")}'..."
  }
}
