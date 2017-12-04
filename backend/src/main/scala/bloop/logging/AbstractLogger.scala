package bloop.logging
import java.util.function.Supplier

abstract class AbstractLogger extends Logger {

  override def debug(msg: Supplier[String]): Unit = debug(msg.get())
  override def error(msg: Supplier[String]): Unit = error(msg.get())
  override def warn(msg: Supplier[String]): Unit = warn(msg.get())
  override def info(msg: Supplier[String]): Unit = info(msg.get)
  override def trace(exception: Supplier[Throwable]): Unit = trace(exception.get())

  override def quietIfError[T](op: BufferedLogger => T): T = verbose {
    val bufferedLogger = new BufferedLogger(this)
    try op(bufferedLogger)
    catch { case ex: Throwable => bufferedLogger.clear(); throw ex }
  }

  override def quietIfSuccess[T](op: BufferedLogger => T): T = verbose {
    val bufferedLogger = new BufferedLogger(this)
    try op(bufferedLogger)
    catch { case ex: Throwable => bufferedLogger.flush(); throw ex }
  }

  override def verboseIf[T](cond: Boolean)(op: => T): T =
    if (cond) verbose(op)
    else op
}
