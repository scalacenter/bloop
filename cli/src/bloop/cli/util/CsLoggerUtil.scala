package bloop.cli.util

import coursier.cache.FileCache
import coursier.cache.loggers.{ProgressBarRefreshDisplay, RefreshDisplay, RefreshLogger}
import coursier.jvm.JavaHome
import coursier.util.Task

object CsLoggerUtil {

  // All of these methods are a bit flaky…

  private lazy val loggerDisplay: RefreshLogger => RefreshDisplay = {
    val m = classOf[RefreshLogger].getDeclaredField("display")
    m.setAccessible(true)
    logger => m.get(logger).asInstanceOf[RefreshDisplay]
  }

  implicit class CsCacheExtensions(private val cache: FileCache[Task]) extends AnyVal {
    def withMessage(message: String): FileCache[Task] =
      cache.logger match {
        case logger: RefreshLogger =>
          val shouldUpdateLogger = loggerDisplay(logger) match {
            case _: CustomProgressBarRefreshDisplay => true
            case _: ProgressBarRefreshDisplay       => true
            case _                                  => false
          }
          if (shouldUpdateLogger) {
            var displayed = false
            val updatedLogger = RefreshLogger.create(
              CustomProgressBarRefreshDisplay.create(
                keepOnScreen = false,
                if (!displayed) {
                  System.err.println(message)
                  displayed = true
                },
                ()
              )
            )
            cache.withLogger(updatedLogger)
          }
          else cache
        case _ => cache
      }
  }
  implicit class CsJavaHomeExtensions(private val javaHome: JavaHome) extends AnyVal {
    def withMessage(message: String): JavaHome =
      javaHome.cache.map(_.archiveCache.cache) match {
        case Some(f: FileCache[Task]) =>
          val cache0 = f.withMessage(message)
          javaHome.withCache(
            javaHome.cache.map(c => c.withArchiveCache(c.archiveCache.withCache(cache0)))
          )
        case _ => javaHome
      }
  }
}
