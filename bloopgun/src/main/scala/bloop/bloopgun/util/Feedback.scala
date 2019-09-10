package bloop.bloopgun.util
import bloop.bloopgun.ServerConfig

object Feedback {
  def unexpectedServerArgsSyntax(obtained: String): String =
    s"""Unexpected server args syntax, got: '$obtained', expected: <port> | <host> <port>"""

  def serverCouldNotBeStarted(config: ServerConfig): String = {
    s"Server could not be started at ${config.host}:${config.port}! Giving up..."
  }
}
