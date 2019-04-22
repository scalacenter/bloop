package bloop.util

import bloop.logging.Logger

import scala.concurrent.duration.FiniteDuration

object SystemProperties {
  private def parseLong(repr: String): Option[Long] = {
    try Some(java.lang.Long.parseLong(repr))
    catch { case _: NumberFormatException => None }
  }

  def getCompileDisconnectionTime(logger: Logger): FiniteDuration = {
    val disconnectionTime = System.getProperty(Keys.SecondsBeforeDisconnectionKey)
    import java.util.concurrent.TimeUnit
    val secondsBeforeDisconnection = {
      if (disconnectionTime == null) Defaults.SecondsBeforeDisconnection
      else {
        parseLong(disconnectionTime).getOrElse {
          logger.warn(
            s"Ignoring non-numeric value for ${Keys.SecondsBeforeDisconnectionKey}, defaulting to ${Defaults.SecondsBeforeDisconnection}"
          )
          Defaults.SecondsBeforeDisconnection
        }
      }
    }

    FiniteDuration(secondsBeforeDisconnection, TimeUnit.SECONDS)
  }

  private[bloop] object Keys {
    val SecondsBeforeDisconnectionKey =
      "bloop.compilation.seconds-before-disconnection"
  }

  private[bloop] object Defaults {
    val SecondsBeforeDisconnection: Long = 30L

  }
}
