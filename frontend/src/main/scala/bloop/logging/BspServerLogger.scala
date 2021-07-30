package bloop.logging

import java.util.concurrent.atomic.AtomicInteger

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.DiagnosticSeverity
import ch.epfl.scala.bsp.Uri
import ch.epfl.scala.bsp.endpoints.Build

import bloop.engine.State

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import jsonrpc4s.RawJson
import jsonrpc4s.RpcClient
import monix.execution.atomic.AtomicInt
import sbt.internal.inc.bloop.ZincInternals
import xsbti.Severity

/**
 * Creates a logger that will forward all the messages to the underlying bsp client.
 * It does so via the replication of the `build/logMessage` LSP functionality.
 *
 * The bsp server logger defines some specific BSP endpoints that can only be used
 * when the logger is known to be of this instance.
 */
final class BspServerLogger private (
    override val name: String,
    private[bloop] val underlying: Logger,
    implicit val client: RpcClient,
    taskIdCounter: AtomicInt,
    ansiSupported: Boolean,
    val originId: Option[String]
) extends Logger
    with ScribeAdapter {
  override def debugFilter: DebugFilter = underlying.debugFilter

  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger =
    new BspServerLogger(name, underlying.asDiscrete, client, taskIdCounter, ansiSupported, originId)
  override def asVerbose: Logger = asBspServerVerbose
  def asBspServerVerbose: BspServerLogger =
    new BspServerLogger(name, underlying.asVerbose, client, taskIdCounter, ansiSupported, originId)
  override def withOriginId(originId: Option[String]): BspServerLogger =
    new BspServerLogger(name, underlying, client, taskIdCounter, ansiSupported, originId)

  override def ansiCodesSupported: Boolean = ansiSupported || underlying.ansiCodesSupported()

  override private[logging] def printDebug(msg: String): Unit = underlying.printDebug(msg)
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit =
    if (debugFilter.isEnabledFor(ctx)) printDebug(msg)

  override def trace(t: Throwable): Unit = underlying.trace(t)

  override def error(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Error, None, originId, msg))
    ()
  }

  override def displayWarningToUser(msg: String): Unit = {
    // Log warning too despite the `logMessage`
    warn(msg)
    // Metals and other clients should be showing `showMessage` to users
    import ch.epfl.scala.bsp.MessageType
    import ch.epfl.scala.bsp.ShowMessageParams
    val showParams = ShowMessageParams(MessageType.Warning, None, originId, msg)
    bsp.endpoints.Build.showMessage.notify(showParams)
    ()
  }

  override def warn(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Warning, None, originId, msg))
    ()
  }

  override def info(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Info, None, originId, msg))
    ()
  }

  private def bspSeverity(problemSeverity: Severity): DiagnosticSeverity = {
    problemSeverity match {
      case Severity.Error => bsp.DiagnosticSeverity.Error
      case Severity.Warn => bsp.DiagnosticSeverity.Warning
      case Severity.Info => bsp.DiagnosticSeverity.Information
    }
  }

  def diagnostic(event: CompilationEvent.Diagnostic): Unit = {
    import sbt.util.InterfaceUtil.toOption
    val message = event.problem.message
    val problemPos = event.problem.position
    val problemSeverity = event.problem.severity
    val sourceFile = toOption(problemPos.sourceFile())
    // TODO: Add code content

    (problemPos, sourceFile) match {
      case (ZincInternals.ZincExistsStartPos(startLine, startColumn), Some(file)) =>
        // Lines in Scalac are indexed by 1, BSP expects 0-index positions
        val pos = problemPos match {
          case ZincInternals.ZincRangePos(endLine, endColumn) =>
            val start = bsp.Position(startLine - 1, startColumn)
            val end = bsp.Position(endLine - 1, endColumn)
            bsp.Range(start, end)
          case _ =>
            val pos = bsp.Position(startLine - 1, startColumn)
            bsp.Range(pos, pos)
        }

        val source = Some("bloop")
        val uri = bsp.Uri(file.toPath.toUri)
        val severity = bspSeverity(problemSeverity)
        val diagnostic = bsp.Diagnostic(pos, Some(severity), None, source, message, None)
        val textDocument = bsp.TextDocumentIdentifier(uri)
        val buildTargetId = bsp.BuildTargetIdentifier(event.projectUri)
        Build.publishDiagnostics.notify(
          bsp.PublishDiagnosticsParams(
            textDocument,
            buildTargetId,
            originId,
            List(diagnostic),
            event.clear
          )
        )
      case (_, Some(file)) =>
        val uri = bsp.Uri(file.toPath.toUri)
        val pos = bsp.Position(0, 0)
        val range = bsp.Range(pos, pos)
        val severity = bspSeverity(problemSeverity)
        val diagnostic = bsp.Diagnostic(range, Some(severity), None, None, message, None)
        val textDocument = bsp.TextDocumentIdentifier(uri)
        val buildTargetId = bsp.BuildTargetIdentifier(event.projectUri)
        Build.publishDiagnostics.notify(
          bsp.PublishDiagnosticsParams(
            textDocument,
            buildTargetId,
            originId,
            List(diagnostic),
            event.clear
          )
        )
      case _ =>
        problemSeverity match {
          case Severity.Error => error(message)
          case Severity.Warn => warn(message)
          case Severity.Info => info(message)
        }
    }
    ()
  }

  def noDiagnostic(event: CompilationEvent.NoDiagnostic): Unit = {
    val uri = bsp.Uri(event.file.toPath.toUri)
    val textDocument = bsp.TextDocumentIdentifier(uri)
    val buildTargetId = bsp.BuildTargetIdentifier(event.projectUri)
    val diagnostics =
      bsp.PublishDiagnosticsParams(textDocument, buildTargetId, None, Nil, true)
    Build.publishDiagnostics.notify(diagnostics)
    ()
  }

  /** Return the next task id per bsp session. */
  def nextTaskId: bsp.TaskId = {
    // TODO(jvican): Add parent information to the task id
    bsp.TaskId(taskIdCounter.addAndGet(1).toString, None)
  }

  private def now: Long = System.currentTimeMillis()

  def publishCompilationStart(event: CompilationEvent.StartCompilation): Unit = {
    val encoded = writeToArray(
      bsp.CompileTask(bsp.BuildTargetIdentifier(event.projectUri))
    )

    Build.taskStart.notify(
      bsp.TaskStartParams(
        event.taskId,
        Some(now),
        Some(event.msg),
        Some(bsp.TaskDataKind.CompileTask),
        Some(RawJson(encoded))
      )
    )
    ()
  }

  private case class BloopProgress(
      target: BuildTargetIdentifier
  )

  private implicit val codec: JsonValueCodec[BloopProgress] =
    JsonCodecMaker.makeWithRequiredCollectionFields

  /** Publish a compile progress notification to the client via BSP every 5% progress increments. */
  def publishCompilationProgress(event: CompilationEvent.ProgressCompilation): Unit = {
    val msg = s"Compiling ${event.projectName} (${event.percentage}%)"
    val encoded = writeToArray(BloopProgress(bsp.BuildTargetIdentifier(event.projectUri)))
    Build.taskProgress.notify(
      bsp.TaskProgressParams(
        event.taskId,
        Some(now),
        Some(msg),
        Some(event.total),
        Some(event.progress),
        None,
        Some("bloop-progress"),
        Some(RawJson(encoded))
      )
    )
    ()
  }

  def publishCompilationEnd(event: CompilationEvent.EndCompilation): Unit = {
    val errors = event.problems.count(_.severity == Severity.Error)
    val warnings = event.problems.count(_.severity == Severity.Warn)
    val encoded = writeToArray(
      BspServerLogger.BloopCompileReport(
        bsp.BuildTargetIdentifier(event.projectUri),
        originId,
        errors,
        warnings,
        None,
        Some(event.isNoOp),
        Some(event.isLastCycle),
        event.clientDir.map(path => bsp.Uri(path.toBspUri)),
        event.analysisOut.map(path => bsp.Uri(path.toBspUri))
      )
    )

    Build.taskFinish.notify(
      bsp.TaskFinishParams(
        event.taskId,
        Some(now),
        Some(s"Compiled '${event.projectName}'"),
        event.code,
        Some(bsp.TaskDataKind.CompileReport),
        Some(RawJson(encoded))
      )
    )
    ()
  }
}

object BspServerLogger {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)

  def apply(
      state: State,
      client: RpcClient,
      taskIdCounter: AtomicInt,
      ansiCodesSupported: Boolean
  ): BspServerLogger = {
    val name: String = s"bsp-logger-${BspServerLogger.counter.incrementAndGet()}"
    new BspServerLogger(name, state.logger, client, taskIdCounter, ansiCodesSupported, None)
  }

  /**
   * A modified version of `bsp.CompileReport` with optional bloop fields.
   *
   * We should consider upstreaming many of these fields as they are usually
   * very useful for clients.
   */
  final case class BloopCompileReport(
      target: BuildTargetIdentifier,
      originId: Option[String],
      errors: Int,
      warnings: Int,
      time: Option[Long],
      // ++ bloop ++
      isNoOp: Option[Boolean],
      isLastCycle: Option[Boolean],
      clientDir: Option[Uri],
      analysisOut: Option[Uri]
  )

  object BloopCompileReport {
    implicit val codec: JsonValueCodec[BloopCompileReport] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }
}
