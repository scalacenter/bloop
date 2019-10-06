// Vendored from https://github.com/lihaoyi/utest
package bloop.testing.ui

/**
 * Defines wrapper-functions that can be used to pretty print stack traces.
 */
object StackMarker {
  @noinline def dropInside[T](t: => T): T = t
  @noinline def dropOutside[T](t: => T): T = t

  def filterCallStack(stack: Seq[StackTraceElement]): Seq[StackTraceElement] = {
    val droppedInside = stack.indexWhere(
      x => x.getClassName == "utest.framework.StackMarker$" && x.getMethodName == "dropInside"
    )

    val droppedOutside = stack.indexWhere(
      x => x.getClassName == "utest.framework.StackMarker$" && x.getMethodName == "dropOutside"
    )

    val stack1 = stack.slice(
      droppedInside match {
        case -1 => 0
        case n => n + 2
      },
      droppedOutside match {
        case -1 => stack.length
        case n => n
      }
    )

    val lastNonLMFIndex = stack1.lastIndexWhere(x => !x.getClassName.contains("$$Lambda$"))

    if (lastNonLMFIndex < 0) stack1
    else stack1.take(lastNonLMFIndex + 1)
  }
}
