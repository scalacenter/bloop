package bloop.logging

import java.util.concurrent.atomic.AtomicInteger

import bloop.engine.State
import bloop.reporter.{DefaultReporterFormat, Problem}
import org.langmeta.jsonrpc.JsonRpcClient
import org.langmeta.lsp
import xsbti.Severity

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
    lsp.Window.showMessage.error(msg)
  }

  override def warn(msg: String): Unit = {
    lsp.Window.showMessage.warn(msg)
  }

  override def info(msg: String): Unit = {
    lsp.Window.showMessage.info(msg)
  }

  def diagnostic(problem: Problem): Unit = {
    val message = problem.message
    val sourceFile = DefaultReporterFormat.toOption(problem.position.sourceFile())
    val rangeAndUri = DefaultReporterFormat
      .position(problem.position)
      .map(lc => lsp.Position(lc._1, lc._2))
      .map(p => lsp.Range(p, p))
      .flatMap(r => sourceFile.map(f => (r, f)))

    rangeAndUri match {
      case Some((range, file)) =>
        val severity = problem.severity match {
          case Severity.Error => lsp.DiagnosticSeverity.Error
          case Severity.Warn => lsp.DiagnosticSeverity.Warning
          case Severity.Info => lsp.DiagnosticSeverity.Information
        }

        val uri = file.toURI.toString
        val diagnostic = lsp.Diagnostic(range, Some(severity), None, None, message)
        val diagnostics = lsp.PublishDiagnostics(uri, List(diagnostic))
        lsp.TextDocument.publishDiagnostics.notify(diagnostics)
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
