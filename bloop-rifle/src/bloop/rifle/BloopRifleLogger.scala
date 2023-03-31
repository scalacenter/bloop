package bloop.rifle

import snailgun.logging.{Logger => SnailgunLogger}

import java.io.OutputStream

trait BloopRifleLogger { self =>
  def info(msg: => String): Unit
  def debug(msg: => String, ex: Throwable): Unit
  final def debug(msg: => String): Unit = debug(msg, null)
  def error(msg: => String, ex: Throwable): Unit
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

  def nailgunLogger: SnailgunLogger =
    new SnailgunLogger {
      val name: String                      = "bloop"
      val isVerbose: Boolean                = true
      def debug(msg: String): Unit          = self.debug("nailgun debug: " + msg)
      def error(msg: String): Unit          = self.debug("nailgun error: " + msg)
      def warn(msg: String): Unit           = self.debug("nailgun warn: " + msg)
      def info(msg: String): Unit           = self.debug("nailgun info: " + msg)
      def trace(exception: Throwable): Unit = self.debug("nailgun trace: " + exception.toString)
    }
}

object BloopRifleLogger {
  def nop: BloopRifleLogger =
    new BloopRifleLogger {
      def info(msg: => String)                 = {}
      def debug(msg: => String, ex: Throwable) = {}
      def error(msg: => String, ex: Throwable) = {}
      def bloopBspStdout                       = None
      def bloopBspStderr                       = None
      def bloopCliInheritStdout                = false
      def bloopCliInheritStderr                = false
    }
}
