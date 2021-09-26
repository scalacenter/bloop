package bloop

import java.io.File
import java.util.concurrent.Callable

import xsbti.GlobalLock

sealed trait ComponentLock extends GlobalLock {
  override def apply[T](file: File, callable: Callable[T]): T = {
    this.synchronized { callable.call() }
  }
}

object BloopComponentsLock extends ComponentLock

object SemanticDBCacheLock extends ComponentLock

object ExpressionCompilerCacheLock extends ComponentLock
