package bloop

import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.reporter.Reporter
import bloop.task.Task
import bloop.tracing.BraveTracer

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
