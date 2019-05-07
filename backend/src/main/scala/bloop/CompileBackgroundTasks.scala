package bloop

import monix.eval.Task
import bloop.io.AbsolutePath
import bloop.tracing.BraveTracer

abstract class CompileBackgroundTasks {
  def trigger(clientClassesDir: AbsolutePath, tracer: BraveTracer): Task[Unit]
}

object CompileBackgroundTasks {
  val empty: CompileBackgroundTasks = {
    new CompileBackgroundTasks {
      def trigger(clientClassesDir: AbsolutePath, tracer: BraveTracer): Task[Unit] = Task.now(())
    }
  }
}
