package bloop

import java.io.File
import java.util.concurrent.Callable

object GlobalLock extends xsbti.GlobalLock {
  override def apply[T](lockFile: File, run: Callable[T]): T =
    run.call()

  def apply[T](lockFile: File)(run: => T): T =
    apply(lockFile, () => run)
}
