package bloop.engine

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.UUID

import bloop.cli.{CliOptions, Commands}
import bloop.logging.BloopLogger
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
    assert(output.contains("Bloop-frontend version"))
    assert(output.contains("Zinc version"))
    assert(output.contains("Scala version"))
    assert(output.contains("maintained by"))
    assert(output.contains("Scala Center"))
  }

  @Test def SupportDynamicCoreSetup(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action1 = Run(Commands.Configure(cliOptions = cliOptions))
    val state1 = TestUtil.blockingExecute(action1, state)
    val output1 = outStream.toString("UTF-8")
    // Make sure that threads are set to 6.
    assert(!output1.contains("Reconfiguring the number of bloop threads to 4."))

    val action2 = Run(Commands.Configure(threads = 6, cliOptions = cliOptions))
    val state2 = TestUtil.blockingExecute(action2, state1)
    val action3 = Run(Commands.Configure(threads = 4, cliOptions = cliOptions))
    val _ = TestUtil.blockingExecute(action3, state2)
    val output23 = outStream.toString("UTF-8")

    // Make sure that threads are set to 6.
    assert(output23.contains("Reconfiguring the number of bloop threads to 6."))
    // Make sure that threads are set to 4.
    assert(output23.contains("Reconfiguring the number of bloop threads to 4."))
  }
}

object InterpreterSpec {
  def changeOut(state: State): (State, CliOptions, ByteArrayOutputStream) = {
    val inMemory = new ByteArrayOutputStream()
    val newOut = new PrintStream(inMemory)
    val loggerName = UUID.randomUUID().toString
    val newLogger = BloopLogger.at(loggerName, newOut, newOut, false)
    val defaultCli = CliOptions.default
    val newCommonOptions = state.commonOptions.copy(out = newOut)
    val newState = state.copy(logger = newLogger, commonOptions = newCommonOptions)
    val newCli = defaultCli.copy(common = newCommonOptions)
    (newState, newCli, inMemory)
  }
}
