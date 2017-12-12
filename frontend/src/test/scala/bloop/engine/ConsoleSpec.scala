package bloop.engine

import java.io.{ByteArrayInputStream, InputStream}

import bloop.ScalaInstance
import bloop.tasks.ProjectHelpers.{RootProject, checkAfterCleanCompilation}
import bloop.cli.{CliOptions, Commands, CommonOptions}
import org.junit.Test
import InterpreterSpec.changeOut

class ConsoleSpec {
  object ArtificialSources {
    val `A.scala` = "package p0\ntrait A"
    val `B.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
  }

  private val LatestScala = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.4")

  def changeIn(commonOptions: CommonOptions): (CliOptions, ByteArrayInputStream) = {
    val inMemory = new ByteArrayInputStream()()
    val defaultCli = CliOptions.default
    defaultCli.copy(common = commonOptions.copy(in = inMemory)) -> inMemory
  }

  @Test
  def RunSimpleConsole(): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))
    checkAfterCleanCompilation(structures, dependencies, LatestScala, quiet = true) {
      (state: State) =>
        val (cliOptions0, out) = changeOut(state)
        val (cliOptions, in) = changeIn(cliOptions0.common)
        val action = Action(Run(Commands.Console(RootProject, cliOptions = cliOptions)))
        Interpreter.execute(action, state)
      }
  }
}
