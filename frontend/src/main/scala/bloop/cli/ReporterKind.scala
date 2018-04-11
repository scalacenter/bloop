package bloop.cli

import bloop.reporter.ReporterConfig
import caseapp.core.ArgParser

/** Represents a reporter kind that users can pick to display compiler messages. */
sealed abstract class ReporterKind(val name: String)
case object ScalacReporter extends ReporterKind("scalac")
case object BloopReporter extends ReporterKind("bloop")

object ReporterKind {
  val reporters: List[ReporterKind] = List(ScalacReporter, BloopReporter)

  implicit val reporterKindRead: ArgParser[ReporterKind] = {
    ArgParser.instance[ReporterKind]("reporter") { input =>
      reporters.find(_.name == input) match {
        case Some(reporter) => Right(reporter)
        case None => Left(s"Unrecognized reporter: $input")
      }
    }
  }

  def toReporterConfig(kind: ReporterKind): ReporterConfig = kind match {
    case ScalacReporter => ReporterConfig.scalacFormat
    case BloopReporter => ReporterConfig.defaultFormat
  }
}
