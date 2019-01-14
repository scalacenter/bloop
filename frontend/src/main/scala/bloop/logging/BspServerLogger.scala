package bloop.logging

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import bloop.data.Project
import bloop.engine.State
import bloop.reporter.Problem
import sbt.internal.inc.bloop.ZincInternals
import xsbti.Severity

import scala.meta.jsonrpc.JsonRpcClient
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.endpoints.Build
import monix.execution.atomic.AtomicInt

/**
 * Creates a logger that will forward all the messages to the underlying bsp client.
 * It does so via the replication of the `build/logMessage` LSP functionality.
 */
final class BspServerLogger private (
    override val name: String,
    underlying: Logger,
    implicit val client: JsonRpcClient,
    taskIdCounter: AtomicInt,
    ansiSupported: Boolean
) extends Logger
    with ScribeAdapter {

  override def debugFilter: DebugFilter = underlying.debugFilter

  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger =
    new BspServerLogger(name, underlying.asDiscrete, client, taskIdCounter, ansiSupported)
  override def asVerbose: Logger =
    new BspServerLogger(name, underlying.asVerbose, client, taskIdCounter, ansiSupported)

  override def ansiCodesSupported: Boolean = ansiSupported || underlying.ansiCodesSupported()

  override private[logging] def printDebug(msg: String): Unit = underlying.printDebug(msg)
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit =
    if (debugFilter.isEnabledFor(ctx)) printDebug(msg)

  override def trace(t: Throwable): Unit = underlying.trace(t)

  override def error(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Error, None, None, msg))
    ()
  }

  override def warn(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Warning, None, None, msg))
    ()
  }

  override def info(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Info, None, None, msg))
    ()
  }

  def diagnostic(project: Project, problem: xsbti.Problem, clear: Boolean): Unit = {
    import sbt.util.InterfaceUtil.toOption
    val message = problem.message
    val problemPos = problem.position
    val sourceFile = toOption(problemPos.sourceFile())

    (problemPos, sourceFile) match {
      case (ZincInternals.ZincExistsStartPos(startLine, startColumn), Some(file)) =>
        // Lines in Scalac are indexed by 1, BSP expects 0-index positions
        val pos = problem.position match {
          case ZincInternals.ZincRangePos(endLine, endColumn) =>
            val start = bsp.Position(startLine - 1, startColumn)
            val end = bsp.Position(endLine - 1, endColumn)
            bsp.Range(start, end)
          case _ =>
            val pos = bsp.Position(startLine - 1, startColumn)
            bsp.Range(pos, pos)
        }

        val severity = problem.severity match {
          case Severity.Error => bsp.DiagnosticSeverity.Error
          case Severity.Warn => bsp.DiagnosticSeverity.Warning
          case Severity.Info => bsp.DiagnosticSeverity.Information
        }

        val uri = bsp.Uri(file.toPath.toUri)
        val diagnostic = bsp.Diagnostic(pos, Some(severity), None, None, message, None)
        val textDocument = bsp.TextDocumentIdentifier(uri)
        val buildTargetId = bsp.BuildTargetIdentifier(project.bspUri)
        val diagnostics =
          bsp.PublishDiagnosticsParams(textDocument, buildTargetId, None, List(diagnostic), clear)
        Build.publishDiagnostics.notify(diagnostics)
      case _ =>
        problem.severity match {
          case Severity.Error => error(message)
          case Severity.Warn => warn(message)
          case Severity.Info => info(message)
        }
    }
    ()
  }

  def noDiagnostic(project: Project, file: File): Unit = {
    val uri = bsp.Uri(file.toPath.toUri)
    val textDocument = bsp.TextDocumentIdentifier(uri)
    val buildTargetId = bsp.BuildTargetIdentifier(project.bspUri)
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

  import io.circe.ObjectEncoder
  private case class BloopProgress(
      target: BuildTargetIdentifier
  )

  private implicit val bloopProgressEncoder: ObjectEncoder[BloopProgress] =
    io.circe.derivation.deriveEncoder

  /** Publish a compile progress notification to the client via BSP every 5% progress increments. */
  def publishCompileProgress(
      taskId: bsp.TaskId,
      project: Project,
      progress: Long,
      total: Long,
      percentage: Long
  ): Unit = {
    val msg = s"Compiling ${project.name} (${percentage}%)"
    val json = bloopProgressEncoder(BloopProgress(bsp.BuildTargetIdentifier(project.bspUri)))
    Build.taskProgress.notify(
      bsp.TaskProgressParams(
        taskId,
        Some(System.currentTimeMillis()),
        Some(msg),
        Some(total),
        Some(progress),
        None,
        Some("bloop-progress"),
        Some(json)
      )
    )
    ()
  }

  /**
   * Publish a compile start notification to the client via BSP.
   *
   * The compile start notification must always have a corresponding
   * compile end notification, published by [[publishCompileEnd()]].
   *
   * @param project The project to which the compilation is associated.
   * @param msg The message summarizing the triggered incremental compilation cycle.
   * @param taskId The task id to use for this publication.
   */
  def publishCompileStart(project: Project, msg: String, taskId: bsp.TaskId): Unit = {
    val json = bsp.CompileTask.encodeCompileTask(
      bsp.CompileTask(bsp.BuildTargetIdentifier(project.bspUri))
    )

    Build.taskStart.notify(
      bsp.TaskStartParams(
        taskId,
        Some(now),
        Some(msg),
        Some(bsp.TaskDataKind.CompileTask),
        Some(json)
      )
    )
    ()
  }

  /**
   * Publish a compile start notification to the client via BSP.
   *
   * The compile end notification must always the same task id than
   * its counterpart, published by [[publishCompileStart()]].
   *
   * @param project The project to which the compilation is associated.
   * @param taskId The task id to use for this publication.
   * @param problems The sequence of problems that were found during compilation.
   * @param code The status code associated with the finished compilation event.
   */
  def publishCompileEnd(
      project: Project,
      taskId: bsp.TaskId,
      problems: Seq[Problem],
      code: bsp.StatusCode
  ): Unit = {
    val errors = problems.count(_.severity == Severity.Error)
    val warnings = problems.count(_.severity == Severity.Warn)
    val json = bsp.CompileReport.encodeCompileReport(
      bsp.CompileReport(bsp.BuildTargetIdentifier(project.bspUri), None, errors, warnings, None)
    )

    Build.taskFinish.notify(
      bsp.TaskFinishParams(
        taskId,
        Some(now),
        Some(s"Compiled '${project.name}'"),
        code,
        Some(bsp.TaskDataKind.CompileReport),
        Some(json)
      )
    )
    ()
  }
}

object BspServerLogger {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)

  def apply(
      state: State,
      client: JsonRpcClient,
      taskIdCounter: AtomicInt,
      ansiCodesSupported: Boolean
  ): BspServerLogger = {
    val name: String = s"bsp-logger-${BspServerLogger.counter.incrementAndGet()}"
    new BspServerLogger(name, state.logger, client, taskIdCounter, ansiCodesSupported)
  }
}
