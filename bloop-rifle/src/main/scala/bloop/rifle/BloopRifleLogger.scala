package bloop.rifle

import java.io.OutputStream

trait BloopRifleLogger { self =>
  def info(msg: => String): Unit
  def debug(msg: => String, ex: Throwable): Unit
  def debug(msg: => String): Unit
  def error(msg: => String, ex: Throwable): Unit
  def error(msg: => String): Unit
  def runnable(name: String)(r: Runnable): Runnable = { () =>
    try r.run()
    catch {
      case e: Throwable =>
        error(s"Error running $name", e)
    }
  }
  def bloopBspStdout: Option[OutputStream]
  def bloopBspStderr: Option[OutputStream]
  def bloopCliInheritStdout: Boolean
  def bloopCliInheritStderr: Boolean
}

object BloopRifleLogger {
  def nop: BloopRifleLogger =
    new BloopRifleLogger {

      def debug(msg: => String, ex: Throwable): Unit = {}
      def info(msg: => String) = {}
      def debug(msg: => String) = {}
      def error(msg: => String, ex: Throwable) = {}
      def error(msg: => String) = {}
      def bloopBspStdout: Option[java.io.OutputStream] = None
      def bloopBspStderr: Option[java.io.OutputStream] = None
      def bloopCliInheritStdout = false
      def bloopCliInheritStderr = false
    }
}
