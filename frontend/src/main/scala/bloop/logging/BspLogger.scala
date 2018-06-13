package bloop.logging

import java.util.concurrent.atomic.AtomicInteger

import bloop.engine.State
import bloop.reporter.{DefaultReporterFormat, Problem}
import sbt.internal.inc.bloop.ZincInternals
import xsbti.Severity

import scala.meta.jsonrpc.JsonRpcClient
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints.Build

/**
 * Creates a logger that will forward all the messages to the underlying bsp client.
 * It does so via the replication of the `window/showMessage` LSP functionality.
 */
final class BspLogger private (
    override val name: String,
    underlying: Logger,
    implicit val client: JsonRpcClient,
    ansiSupported: Boolean
) extends Logger {

  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger =
    new BspLogger(name, underlying.asDiscrete, client, ansiSupported)
  override def asVerbose: Logger =
    new BspLogger(name, underlying.asVerbose, client, ansiSupported)

  override def ansiCodesSupported: Boolean = ansiSupported || underlying.ansiCodesSupported()
  override def debug(msg: String): Unit = underlying.debug(msg)
  override def trace(t: Throwable): Unit = underlying.trace(t)

  override def error(msg: String): Unit = {
    Build.showMessage.notify(bsp.ShowMessageParams(bsp.MessageType.Error, None, None, msg))
  }

  override def warn(msg: String): Unit = {
    Build.showMessage.notify(bsp.ShowMessageParams(bsp.MessageType.Warning, None, None, msg))
  }

  override def info(msg: String): Unit = {
    Build.showMessage.notify(bsp.ShowMessageParams(bsp.MessageType.Info, None, None, msg))
  }

  def diagnostic(problem: Problem): Unit = {
    val message = problem.message
    val sourceFile = DefaultReporterFormat.toOption(problem.position.sourceFile())
    val rangeAndUri = DefaultReporterFormat
      .position(problem.position)
      .flatMap(p => sourceFile.map(f => (p, f)))

    rangeAndUri match {
      case Some((pos0, file)) =>
        val pos = ZincInternals.rangeFromPosition(problem.position) match {
          case Some(lineRange) =>
            val start = bsp.Position(pos0.line, lineRange.start)
            val end = bsp.Position(pos0.line, lineRange.end)
            bsp.Range(start, end)
          case None =>
            val pos = bsp.Position(pos0.line, pos0.column)
            bsp.Range(pos, pos)
        }

        val severity = problem.severity match {
          case Severity.Error => bsp.DiagnosticSeverity.Error
          case Severity.Warn => bsp.DiagnosticSeverity.Warning
          case Severity.Info => bsp.DiagnosticSeverity.Information
        }

        val uri = bsp.Uri(file.toURI)
        val diagnostic = bsp.Diagnostic(pos, Some(severity), None, None, message, None)
        val diagnostics = bsp.PublishDiagnosticsParams(uri, None, List(diagnostic))
        Build.publishDiagnostics.notify(diagnostics)
      case None =>
        problem.severity match {
          case Severity.Error => error(message)
          case Severity.Warn => warn(message)
          case Severity.Info => info(message)
        }
    }
  }
}

object BspLogger {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)

  def apply(state: State, client: JsonRpcClient, ansiCodesSupported: Boolean): BspLogger = {
    val name: String = s"bsp-logger-${BspLogger.counter.incrementAndGet()}"
    new BspLogger(name, state.logger, client, ansiCodesSupported)
  }
}
