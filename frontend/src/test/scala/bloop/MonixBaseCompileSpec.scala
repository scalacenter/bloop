package bloop

import bloop.Cli.CliSession
import bloop.cli.{CommonOptions, ExitStatus}
import bloop.engine.NoPool
import bloop.io.Environment.{LineSplitter, lineSeparator}
import bloop.logging.RecordingLogger
import monix.eval.Task
import bloop.testing.DiffAssertions
import bloop.util.{BaseTestProject, TestUtil}
import cats.effect.concurrent.Ref

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Path

abstract class MonixBaseCompileSpec extends bloop.testing.MonixBaseSuite {
  protected def TestProject: BaseTestProject

  protected def extraCompilationMessageOutput: String = ""
  protected def processOutput(output: String) = output

  test("don't compile build in two concurrent CLI clients") {
    TestUtil.withinWorkspaceV2 { workspace =>
      val sources = List(
        """/main/scala/Faa.scala
          |class Faa
          """.stripMargin
      )
      val testOut = new ByteArrayOutputStream()
      val options = CommonOptions.default.copy(out = new PrintStream(testOut))
      val `A` = TestProject(workspace, "z", sources)
      val configDir = TestProject.populateWorkspace(workspace, List(`A`))
      val compileArgs =
        Array("compile", "z", "--config-dir", configDir.syntax, "--verbose")
      val compileAction = Cli.parse(compileArgs, options)
      def runCompileAsync(activeSessions: Ref[Task, Map[Path, List[CliSession]]], postfix: String): Task[ExitStatus] =
        Cli.run(compileAction, NoPool, activeSessions, Option(postfix))
      for {
        activeSessions <- Ref.of[Task, Map[Path, List[CliSession]]](Map.empty)
        _ <- Task.parSequenceUnordered(List(runCompileAsync(activeSessions, "left"), runCompileAsync(activeSessions, "right")))
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
            s"""Compiling z (1 Scala source)
               |Deduplicating compilation of z from cli client ??? (since ???
               |Compiling z (1 Scala source)
               |$extraCompilationMessageOutput
               |""".stripMargin
          )
        } catch {
          case _: DiffAssertions.TestFailedException =>
            assertNoDiff(
              processOutput(obtained),
              s"""
                 |Deduplicating compilation of z from cli client ??? (since ???
                 |Compiling z (1 Scala source)
                 |Compiling z (1 Scala source)
                 |$extraCompilationMessageOutput
                 |""".stripMargin
            )
        }
      }
    }
  }

}
