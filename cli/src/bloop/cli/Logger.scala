package bloop.cli

import bloop.rifle.BloopRifleLogger

trait Logger {
  def error(message: String): Unit
  // TODO Use macros for log and debug calls to have zero cost when verbosity <= 0
  def message(message: => String): Unit
  def log(s: => String): Unit
  def debug(s: => String): Unit

  def coursierLogger(printBefore: String): coursier.cache.CacheLogger
  def bloopRifleLogger: BloopRifleLogger
}

object Logger {
  private class Nop extends Logger {
    def error(message: String): Unit      = ()
    def message(message: => String): Unit = ()
    def log(s: => String): Unit           = ()
    def debug(s: => String): Unit         = ()

    def coursierLogger(printBefore: String): coursier.cache.CacheLogger =
      coursier.cache.CacheLogger.nop
    def bloopRifleLogger: BloopRifleLogger =
      BloopRifleLogger.nop
  }
  def nop: Logger = new Nop
}
