package bloop

import monix.eval.Task

import bloop.io.AbsolutePath
import bloop.tracing.BraveTracer
import bloop.reporter.Reporter
import bloop.logging.Logger

abstract class CompileBackgroundTasks {
  def trigger(
      clientClassesDir: AbsolutePath,
      clientReporter: Reporter,
      clientTracer: BraveTracer,
      clientLogger: Logger
  ): Task[Unit]
}

object CompileBackgroundTasks {
  type Sig = (AbsolutePath, Reporter, BraveTracer) => Task[Unit]
  val empty: CompileBackgroundTasks = {
    new CompileBackgroundTasks {
      def trigger(
          clientClassesDir: AbsolutePath,
          clientReporter: Reporter,
          clientTracer: BraveTracer,
          clientLogger: Logger
      ): Task[Unit] = Task.now(())
    }
  }
}
