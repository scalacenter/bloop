package bloop.engine

import bloop.io.AbsolutePath

sealed trait ClientSession
object ClientSession {
  case class Cli(id: Long, buildUri: AbsolutePath) extends ClientSession
  case class Bsp(id: Long, buildUri: AbsolutePath) extends ClientSession
}
