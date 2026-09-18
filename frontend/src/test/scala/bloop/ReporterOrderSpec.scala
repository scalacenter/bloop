package bloop

import bloop.cli.Commands
import bloop.cli.ScalacReporter
import bloop.util.TestUtil

import org.junit.Assert
import org.junit.Test

class ReporterOrderSpec {
  // Two independent type errors in source order: the first requires a `String`,
  // the second requires an `Int`, so each error is identifiable by its message.
  private val structure = Map(
    "A" -> Map(
      "A.scala" ->
        """package p
          |object A {
          |  val first: String = 1
          |  val second: Int = "s"
          |}
          |""".stripMargin
    )
  )
  private val deps = Map.empty[String, Set[String]]

  private def indexOfError(messages: List[(String, String)], snippet: String): Int = {
    val errors = messages.collect { case ("error", msg) => msg }
    val index = errors.indexWhere(_.contains(snippet))
    Assert.assertTrue(
      s"Missing error containing '$snippet' in:\n${errors.mkString("\n")}",
      index >= 0
    )
    index
  }

  @Test
  def defaultReporterPrintsErrorsInReverseOrder(): Unit = {
    TestUtil.testState(structure, deps) { state =>
      TestUtil.runAndCheck(state, Commands.Compile(List("A"))) { messages =>
        val firstError = indexOfError(messages, "required: String")
        val secondError = indexOfError(messages, "required: Int")
        Assert.assertTrue(
          "Expected errors in reverse order of occurrence by default",
          secondError < firstError
        )
      }
    }
  }

  @Test
  def reverseOrderFalsePrintsErrorsInOccurrenceOrder(): Unit = {
    TestUtil.testState(structure, deps) { state =>
      val compile = Commands.Compile(List("A"), reverseOrder = Some(false))
      TestUtil.runAndCheck(state, compile) { messages =>
        val firstError = indexOfError(messages, "required: String")
        val secondError = indexOfError(messages, "required: Int")
        Assert.assertTrue(
          "Expected errors in order of occurrence with --reverse-order=false",
          firstError < secondError
        )
      }
    }
  }

  // Without the flag the reporter's own order must win. The bloop reporter reverses, so only the
  // scalac reporter can catch a default that is hardcoded instead of read from the reporter.
  @Test
  def absentFlagKeepsTheScalacReporterOrder(): Unit = {
    TestUtil.testState(structure, deps) { state =>
      val compile = Commands.Compile(List("A"), reporter = ScalacReporter)
      TestUtil.runAndCheck(state, compile) { messages =>
        val firstError = indexOfError(messages, "required: String")
        val secondError = indexOfError(messages, "required: Int")
        Assert.assertTrue(
          "Expected the scalac reporter to keep its own occurrence order when the flag is absent",
          firstError < secondError
        )
      }
    }
  }
}
