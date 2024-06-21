package bloop.cli.options

import caseapp._
import coursier.cache.loggers.RefreshLogger
import bloop.rifle.BloopRifleLogger
import bloop.cli.Logger
import java.io.OutputStream

// format: off
final case class LoggingOptions(
  @HelpMessage("Increase verbosity (can be specified multiple times)")
  @Name("v")
    verbose: Int @@ Counter = Tag.of(0),
  @HelpMessage("Decrease verbosity")
  @Name("q")
    quiet: Boolean = false,
  @HelpMessage("Use progress bars")
    progress: Option[Boolean] = None
) {
  // format: on

  lazy val verbosity = Tag.unwrap(verbose) - (if (quiet) 1 else 0)

  def logger: Logger =
    new Logger {
      def error(message: String) =
        System.err.println(message)
      def message(message: => String) =
        if (verbosity >= 0)
          System.err.println(message)
      def log(s: => String) =
        if (verbosity >= 0)
          System.err.println(s)
      def debug(s: => String) =
        if (verbosity >= 2)
          System.err.println(s)

      def coursierLogger(printBefore: String): coursier.cache.CacheLogger =
        RefreshLogger.create()
      def bloopRifleLogger: BloopRifleLogger =
        new BloopRifleLogger {
          def bloopBspStderr: Option[OutputStream] = None
          def bloopBspStdout: Option[OutputStream] = None
          def bloopCliInheritStderr: Boolean = false
          def bloopCliInheritStdout: Boolean = false
          def debug(msg: => String): Unit =
            if (verbosity >= 2) {
              System.err.println(msg)
            }
          def debug(msg: => String, ex: Throwable): Unit =
            if (verbosity >= 2) {
              System.err.println(msg)
              if (verbosity >= 3)
                ex.printStackTrace(System.err)
            }
          def error(msg: => String, ex: Throwable) = {
            System.err.println(msg)
            if (verbosity >= 1)
              ex.printStackTrace(System.err)
          }
          def error(msg: => String) =
            System.err.println(msg)
          def info(msg: => String) =
            if (verbosity >= 0)
              System.err.println(msg)
        }
    }
}

object LoggingOptions {
  lazy val parser: Parser[LoggingOptions] = Parser.derive
  implicit lazy val parserAux: Parser.Aux[LoggingOptions, parser.D] = parser
  implicit lazy val help: Help[LoggingOptions] = Help.derive
}
