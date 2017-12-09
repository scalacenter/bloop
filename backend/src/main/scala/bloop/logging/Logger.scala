package bloop.logging

trait Logger extends xsbti.Logger with sbt.testing.Logger {
  def name: String
  def quietIfError[T](op: BufferedLogger => T): T
  def quietIfSuccess[T](op: BufferedLogger => T): T
  def verboseIf[T](cond: Boolean)(op: => T): T
  def verbose[T](op: => T): T
}

object Logger {
  val name = "bloop"
  def get = new BloopLogger(name)
}
