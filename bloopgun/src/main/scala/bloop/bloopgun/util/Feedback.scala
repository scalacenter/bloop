package bloop.bloopgun.util
import bloop.bloopgun.ServerConfig

object Feedback {
  def unexpectedServerArgsSyntax(obtained: String): String =
    s"""Unexpected server args syntax, got: '$obtained', expected: <port> | <host> <port>"""

  def startingBloopServer(cmd: List[String]): String = {
    val suffix = if (cmd.isEmpty) "" else s" with '${cmd.mkString(" ")}'..."
    s"Starting the bloop server$suffix"
  }

  def serverCouldNotBeStarted(config: ServerConfig): String = {
    s"Server could not be started at ${config.host}:${config.port}! Giving up..."
  }
}
