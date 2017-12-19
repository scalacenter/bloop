package bloop.engine

import java.io.{ByteArrayOutputStream, PrintStream}

import bloop.cli.{CliOptions, Commands}
import bloop.tasks.ProjectHelpers
import org.junit.Test
import guru.nidi.graphviz.parse.Parser

class InterpreterSpec {
  private final val state = ProjectHelpers.loadTestProject("sbt")
  import InterpreterSpec.changeOut

  @Test def ShowDotGraphOfSbtProjects(): Unit = {
    val (cliOptions, outStream) = changeOut(state)
    val action = Run(Commands.Projects(dotGraph = true, cliOptions))
    Interpreter.execute(action, state)

    val dotGraph = outStream.toString("UTF-8")
    val graph = Parser.read(dotGraph)
    assert(graph.isDirected, "Dot graph for sbt is not directed")
    ()
  }

  @Test def ShowProjectsInCustomCommonOptions(): Unit = {
    val (cliOptions, outStream) = changeOut(state)
    val action = Run(Commands.Projects(cliOptions = cliOptions))
    Interpreter.execute(action, state)
    val output = outStream.toString("UTF-8")
    assert(output.contains("Projects loaded from"), "Loaded projects were not shown on the logger.")
  }

  @Test def ShowAbout(): Unit = {
    val (cliOptions, outStream) = changeOut(state)
    val action = Run(Commands.About(cliOptions = cliOptions))
    Interpreter.execute(action, state)
    val output = outStream.toString("UTF-8")
    assert(output.contains("Bloop-frontend version"))
    assert(output.contains("Zinc version"))
    assert(output.contains("Scala version"))
    assert(output.contains("maintained by"))
    assert(output.contains("Scala Center"))
  }
}

object InterpreterSpec {
  def changeOut(state: State): (CliOptions, ByteArrayOutputStream) = {
    val inMemory = new ByteArrayOutputStream()
    val newOut = new PrintStream(inMemory)
    val defaultCli = CliOptions.default
    defaultCli.copy(common = state.commonOptions.copy(out = newOut)) -> inMemory
  }
}
