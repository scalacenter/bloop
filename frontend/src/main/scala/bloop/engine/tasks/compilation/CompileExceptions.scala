package bloop.engine.tasks.compilation

import scala.util.control.NoStackTrace

private[tasks] object CompileExceptions {
  abstract class CompileException(msg: String) extends RuntimeException(msg) with NoStackTrace
  object FailPromise extends CompileException("Promise completed after compilation error")
  object CompletePromise extends CompileException("Promise completed after compilation")
  object BlockURI extends CompileException("URI cannot complete: compilation is blocked")
}
