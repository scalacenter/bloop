package bloop.cli

/** Represents a reporter kind that users can pick to display compiler messages. */
sealed trait ReporterKind
case object ScalacReporter extends ReporterKind
case object BloopReporter extends ReporterKind
