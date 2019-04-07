package bloop.testing

import bloop.util.Diff

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import utest.ufansi.Color

// Borrowed from scalameta/scalameta to experiment a bit with richer test infra
object DiffAssertions {
  class TestFailedException(msg: String) extends Exception(msg)
  def assertNoDiffOrPrintObtained(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String
  )(implicit source: sourcecode.Line): Unit = {
    orPrintObtained(
      () => { assertNoDiff(obtained, expected, obtainedTitle, expectedTitle); () },
      obtained
    )
  }

  def assertNoDiffOrPrintExpected(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String,
      print: Boolean = true
  )(
      implicit source: sourcecode.Line
  ): Boolean = {
    try assertNoDiff(obtained, expected, obtainedTitle, expectedTitle)
    catch {
      case ex: Exception =>
        if (print) {
          obtained.linesIterator.toList match {
            case head +: tail =>
              val b = new StringBuilder()
              b.++=("    \"\"\"|" + head).++=(System.lineSeparator())
              tail.foreach { line =>
                b.++=("       |")
                  .++=(line)
                  .++=(System.lineSeparator())
              }
              b.++=("       |\"\"\".stripMargin").++=(System.lineSeparator())
              println(b.mkString)
            case head +: Nil =>
              println(head)
            case Nil =>
              println("obtained is empty")
          }
        }
        throw ex
    }
  }

  def assertNoDiff(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String
  )(implicit source: sourcecode.Line): Boolean = colored {
    if (obtained.isEmpty && !expected.isEmpty) fail("Obtained empty output!")
    val result = Diff.unifiedDiff(obtained, expected, obtainedTitle, expectedTitle)
    if (result.isEmpty) true
    else {
      throw new TestFailedException(
        error2message(
          obtained,
          expected,
          obtainedTitle,
          expectedTitle
        )
      )
    }
  }

  private def error2message(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String
  ): String = {
    def header[T](t: T): String = {
      val line = s"=" * (t.toString.length + 3)
      s"$line\n=> $t\n$line"
    }
    def stripTrailingWhitespace(str: String): String =
      str.replaceAll(" \n", "∙\n")
    val sb = new StringBuilder
    if (obtained.length < 1000) {
      sb.append(
        s"""#${header("Obtained")}
           #${stripTrailingWhitespace(obtained)}
           #
            #""".stripMargin('#')
      )
    }
    sb.append(
      s"""#${header("Diff")}
         #${stripTrailingWhitespace(
           Diff.unifiedDiff(obtained, expected, obtainedTitle, expectedTitle)
         )}"""
        .stripMargin('#')
    )
    sb.toString()
  }

  def colored[T](
      thunk: => T
  )(implicit filename: sourcecode.File, line: sourcecode.Line): T = {
    try {
      thunk
    } catch {
      case NonFatal(e) =>
        val message = e.getMessage.linesIterator
          .map { line =>
            if (line.startsWith("+")) Color.Green(line)
            else if (line.startsWith("-")) Color.LightRed(line)
            else Color.Reset(line)
          }
          .mkString("\n")
        val location = s"failed assertion at ${filename.value}:${line.value}\n"
        throw new TestFailedException(location + message)
    }
  }

  def orPrintObtained(thunk: () => Unit, obtained: String): Unit = {
    try thunk()
    catch {
      case ex: Exception =>
        obtained.linesIterator.toList match {
          case head +: tail =>
            println("    \"\"\"|" + head)
            tail.foreach(line => println("       |" + line))
          case head +: Nil =>
            println(head)
          case Nil =>
            println("obtained is empty")
        }
        throw ex
    }
  }

  def fail(msg: String, stackBump: Int = 0): Nothing = {
    val ex = new DiffAssertions.TestFailedException(msg)
    ex.setStackTrace(ex.getStackTrace.slice(1 + stackBump, 2 + stackBump))
    throw ex
  }
}
