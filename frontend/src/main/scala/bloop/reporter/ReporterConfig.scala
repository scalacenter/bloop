package bloop.reporter

/**
 * General configuration for the formats.
 * Formats are supposed to be opinionated, and they are free to ignore any
 * of those settings.
 */
final case class ReporterConfig(
                                /** `true` to enable colors, `false` to disable them. */
                                colors: Boolean,
                                /** `true` to strip the base directory, `false` to show the full path. */
                                shortenPaths: Boolean,
                                /** `true` to show the column number, `false` to hide it. */
                                columnNumbers: Boolean,
                                /** `true` to show the errors in reverse order, `false` to show them in FIFO order. */
                                reverseOrder: Boolean,
                                /** `true` to show a legend explaining the output of the reporter, `false` to hide it. */
                                showLegend: Boolean,
                                /** The color to use to show errors. */
                                errorColor: String,
                                /** The color to use to show warnings. */
                                warningColor: String,
                                /** The color to use to show information messages. */
                                infoColor: String,
                                /** The color to use to show debug messages. */
                                debugColor: String,
                                /** The color to use to highlight the path where a message was triggered. */
                                sourcePathColor: String,
                                /** The color to use to show an error ID. */
                                errorIdColor: String,
                                /** The format to use. */
                                format: ConfigurableReporter => ReporterFormat)

object ReporterConfig {

  lazy val defaultFormat =
    new ReporterConfig(
      true,
      true,
      false,
      true,
      false,
      scala.Console.RED,
      scala.Console.YELLOW,
      scala.Console.CYAN,
      scala.Console.WHITE,
      scala.Console.UNDERLINED,
      scala.Console.BLUE,
      DefaultReporterFormat
    )

  lazy val scalacFormat =
    new ReporterConfig(
      true,
      true,
      false,
      false,
      true,
      scala.Console.RED,
      scala.Console.YELLOW,
      scala.Console.CYAN,
      scala.Console.WHITE,
      scala.Console.UNDERLINED,
      scala.Console.BLUE,
      ScalacFormat
    )
}
