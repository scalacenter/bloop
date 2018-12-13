package bloop.cli

import scala.collection.mutable

// Copied from scalafix-cli, but slightly modified
sealed abstract case class ExitStatus(code: Int, name: String) {
  def isOk: Boolean = code == ExitStatus.Ok.code
  override def toString: String = s"$name=$code"
}

object ExitStatus {
  private var counter = 0
  private val allInternal = mutable.ListBuffer.empty[ExitStatus]
  private val cache = new java.util.concurrent.ConcurrentHashMap[Int, ExitStatus]
  private def generateExitStatus(implicit name: sourcecode.Name) = {
    val code = counter
    counter = if (counter == 0) 1 else counter << 1
    val result = new ExitStatus(code, name.value) {}
    allInternal += result
    result
  }

  // FORMAT: OFF
  val Ok, UnexpectedError, ParseError, InvalidCommandLineOption, CompilationError, LinkingError, TestExecutionError, RunError, BuildDefinitionError: ExitStatus = generateExitStatus
  // FORMAT: ON

  // The initialization of all must come after the invocations to `generateExitStatus`
  private lazy val all: List[ExitStatus] = allInternal.toList
  private[bloop] def codeToName(code: Int): String = {
    if (code == 0) Ok.name
    else {
      val names = all.collect { case exit if (exit.code & code) != 0 => exit.name }
      names.mkString("+")
    }
  }

  def apply(code: Int): ExitStatus = {
    if (cache.contains(code)) cache.get(code)
    else {
      val result = new ExitStatus(code, codeToName(code)) {}
      cache.put(code, result)
      result
    }
  }

  def merge(exit1: ExitStatus, exit2: ExitStatus): ExitStatus =
    apply(exit1.code | exit2.code)
}
