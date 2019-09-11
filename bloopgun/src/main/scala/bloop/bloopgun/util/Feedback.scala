package bloop.bloopgun.util
import bloop.bloopgun.ServerConfig

object Feedback {
  val DetectedBloopInstallation: String =
    "A bloop installation has been detected either in the PATH or $HOME/.bloop"

  def unexpectedServerArgsSyntax(obtained: String): String =
    s"""Unexpected server args syntax, got: '$obtained', expected: <port> | <host> <port>"""

  def serverCouldNotBeStarted(config: ServerConfig): String = {
    s"Server could not be started at ${config.host}:${config.port}! Giving up..."
  }

  def startingBloopServer(config: ServerConfig): String = {
    s"Starting bloop server at $config..."
  }

  def resolvingDependency(dependency: String): String = s"Resolving $dependency..."
}
