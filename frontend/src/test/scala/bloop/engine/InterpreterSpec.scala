package bloop.engine

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.UUID

import bloop.cli.{CliOptions, Commands}
import bloop.logging.{DebugFilter, BloopLogger}
import bloop.tasks.TestUtil
import org.junit.Test
import org.junit.experimental.categories.Category
import guru.nidi.graphviz.parse.Parser

@Category(Array(classOf[bloop.FastTests]))
class InterpreterSpec {
  private final val initialState = TestUtil.loadTestProject("sbt")
  import InterpreterSpec.changeOut

  @Test def ShowDotGraphOfSbtProjects(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action = Run(Commands.Projects(dotGraph = true, cliOptions))
    TestUtil.blockingExecute(action, state)

    val dotGraph = outStream.toString("UTF-8")
    val graph = Parser.read(dotGraph)
    assert(graph.isDirected, "Dot graph for sbt is not directed")
    ()
  }

  @Test def ShowProjectsInCustomCommonOptions(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action = Run(Commands.Projects(cliOptions = cliOptions))
    TestUtil.blockingExecute(action, state)
    val output = outStream.toString("UTF-8")
    assert(output.contains("sbtRoot"), "Loaded projects were not shown on the logger.")
  }

  @Test def ShowAbout(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action = Run(Commands.About(cliOptions = cliOptions))
    TestUtil.blockingExecute(action, state)
    val output = outStream.toString("UTF-8")
    assert(output.contains("Bloop version"))
    assert(output.contains("Zinc version"))
    assert(output.contains("Scala version"))
    assert(output.contains("maintained by"))
    assert(output.contains("Scala Center"))
  }
}

object InterpreterSpec {
  def changeOut(state: State): (State, CliOptions, ByteArrayOutputStream) = {
    val inMemory = new ByteArrayOutputStream()
    val newOut = new PrintStream(inMemory)
    val loggerName = UUID.randomUUID().toString
    val newLogger = BloopLogger.at(loggerName, newOut, newOut, false, DebugFilter.All)
    val defaultCli = CliOptions.default
    val newCommonOptions = state.commonOptions.copy(out = newOut)
    val newState = state.copy(logger = newLogger, commonOptions = newCommonOptions)
    val newCli = defaultCli.copy(common = newCommonOptions)
    (newState, newCli, inMemory)
  }
}
