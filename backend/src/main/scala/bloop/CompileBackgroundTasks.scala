package bloop

import monix.eval.Task

import bloop.io.AbsolutePath
import bloop.tracing.BraveTracer
import bloop.reporter.Reporter

abstract class CompileBackgroundTasks {
  def trigger(
      clientClassesDir: AbsolutePath,
      reporter: Reporter,
      tracer: BraveTracer
  ): Task[Unit]
}

object CompileBackgroundTasks {
  type Sig = (AbsolutePath, Reporter, BraveTracer) => Task[Unit]
  val empty: CompileBackgroundTasks = {
    new CompileBackgroundTasks {
      def trigger(
          clientClassesDir: AbsolutePath,
          reporter: Reporter,
          tracer: BraveTracer
      ): Task[Unit] = Task.now(())
    }
  }
}
