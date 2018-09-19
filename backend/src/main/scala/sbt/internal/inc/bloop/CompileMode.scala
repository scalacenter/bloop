package sbt.internal.inc.bloop

import java.net.URI
import java.util.Optional
import java.util.concurrent.CompletableFuture

import monix.eval.Task

/**
 * Defines the mode in which compilation should run.
 *
 * There are currently four modes:
 * 1. Sequential (no inputs are required).
 * 2. Parallel (requires the number of batches of source files to parallelize).
 * 3. Pipelined (requires the pickle URI to trigger the dependent compilations and a task to signal
 *    the compilation of Java).
 * 4. Parallel + Pipelined.
 */
sealed trait CompileMode

object CompileMode {
  sealed trait ConfigurableMode extends CompileMode
  case object Sequential extends ConfigurableMode
  final case class Parallel(batches: Int) extends ConfigurableMode

  final case class Pipelined(
      pickleURI: CompletableFuture[Optional[URI]],
      completeJavaCompilation: CompletableFuture[Unit],
      fireJavaCompilation: Task[JavaSignal]
  ) extends CompileMode

  final case class ParallelAndPipelined(
      batches: Int,
      pickleURI: CompletableFuture[Optional[URI]],
      completeJavaCompilation: CompletableFuture[Unit],
      fireJavaCompilation: Task[JavaSignal]
  ) extends CompileMode
}
