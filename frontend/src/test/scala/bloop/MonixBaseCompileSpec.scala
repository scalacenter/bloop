package bloop

import bloop.cli.{CommonOptions, ExitStatus}
import bloop.engine.NoPool
import bloop.io.Environment.{LineSplitter, lineSeparator}
import bloop.logging.RecordingLogger
import monix.eval.Task
import bloop.testing.DiffAssertions
import bloop.util.{BaseTestProject, TestUtil}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

abstract class MonixBaseCompileSpec extends bloop.testing.MonixBaseSuite {
  protected def TestProject: BaseTestProject

  protected def extraCompilationMessageOutput: String = ""
  protected def processOutput(output: String) = output

  test("don't compile build in two concurrent CLI clients") {
    TestUtil.withinWorkspaceV2 { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )
      val testOut = new ByteArrayOutputStream()
      val options = CommonOptions.default.copy(out = new PrintStream(testOut))
      val `A` = TestProject(workspace, "a", sources)
      val configDir = TestProject.populateWorkspace(workspace, List(`A`))
      val compileArgs =
        Array("compile", "a", "--config-dir", configDir.syntax)
      val compileAction = Cli.parse(compileArgs, options)
      def runCompileAsync: Task[ExitStatus] = Cli.run(compileAction, NoPool)
      for {
        runCompile <- Task.parSequenceUnordered(List(runCompileAsync, runCompileAsync))
      } yield {

        val actionsOutput = new String(testOut.toByteArray, StandardCharsets.UTF_8)
        def removeAsciiColorCodes(line: String): String = line.replaceAll("\u001B\\[[;\\d]*m", "")

        val obtained = actionsOutput.splitLines
          .filterNot(_.startsWith("Compiled"))
          .map(removeAsciiColorCodes)
          .map(msg => RecordingLogger.replaceTimingInfo(msg))
          .mkString(lineSeparator)
          .replaceAll("'(bloop-cli-.*)'", "'bloop-cli'")
          .replaceAll("'bloop-cli'", "???")

        try {
          assertNoDiff(
            processOutput(obtained),
            s"""Compiling a (1 Scala source)
               |Deduplicating compilation of a from cli client ??? (since ???
               |Compiling a (1 Scala source)
               |$extraCompilationMessageOutput
               |""".stripMargin
          )
        } catch {
          case _: DiffAssertions.TestFailedException =>
            assertNoDiff(
              processOutput(obtained),
              s"""
                 |Deduplicating compilation of a from cli client ??? (since ???
                 |Compiling a (1 Scala source)
                 |Compiling a (1 Scala source)
                 |$extraCompilationMessageOutput
                 |""".stripMargin
            )
        }
      }
    }
  }

}
