package bloop.logging

import ch.epfl.scala.bsp
import bloop.reporter.Problem
import java.io.File

sealed abstract class BspServerEvent

object BspServerEvent {

  /**
   * Defines all information required to report a compile start notification
   * to the BSP client.
   *
   * The compile start notification must always have a corresponding compile
   * end notification, defined by [[EndCompilation]].
   *
   * @param projectName The project name, useful when enriching messages to the client.
   * @param projectUri The URI uniquely representing the project to the BSP client.
   * @param msg The message summarizing the triggered incremental compilation cycle.
   * @param taskId The task id to use for this publication.
   */
  case class StartCompilation(
      projectName: String,
      projectUri: bsp.Uri,
      msg: String,
      taskId: bsp.TaskId
  ) extends BspServerEvent

  /**
   * Defines all information required to report a compile progress
   * notification to the BSP client.
   *
   * The compile progress notification must always the same task id than
   * [[StartCompilation]] and [[EndCompilation]].
   *
   * @param projectName The project name, useful when enriching messages to the client.
   * @param projectUri The URI uniquely representing the project to the BSP client.
   * @param taskId The task id to use for this publication.
   * @param problems The sequence of problems that were found during compilation.
   * @param code The status code associated with the finished compilation event.
   */
  case class ProgressCompilation(
      projectName: String,
      projectUri: bsp.Uri,
      taskId: bsp.TaskId,
      progress: Long,
      total: Long,
      percentage: Long
  ) extends BspServerEvent

  /**
   * Defines all information required to report a compile end notification to
   * the BSP client.
   *
   * The compile end notification must always the same task id than its
   * counterpart, defined by [[StartCompilation]].
   *
   * @param projectName The project name, useful when enriching messages to the client.
   * @param projectUri The URI uniquely representing the project to the BSP client.
   * @param taskId The task id to use for this publication.
   * @param problems The sequence of problems that were found during compilation.
   * @param code The status code associated with the finished compilation event.
   */
  case class EndCompilation(
      projectName: String,
      projectUri: bsp.Uri,
      taskId: bsp.TaskId,
      problems: Seq[Problem],
      code: bsp.StatusCode
  ) extends BspServerEvent

  case class Diagnostic(
      projectUri: bsp.Uri,
      problem: xsbti.Problem,
      clear: Boolean
  ) extends BspServerEvent

  case class NoDiagnostic(
      projectUri: bsp.Uri,
      file: File
  ) extends BspServerEvent
}
