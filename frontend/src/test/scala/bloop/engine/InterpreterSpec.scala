package bloop.engine

import java.io.{ByteArrayOutputStream, PrintStream}

import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.logging.Logger
import bloop.tasks.ProjectHelpers
import org.junit.Test
import guru.nidi.graphviz.parse.Parser

class InterpreterSpec {
  @Test def ShowSbtProjects(): Unit = {
    val state = ProjectHelpers.loadTestProject("sbt", Logger.get)
    val defaultCli = CliOptions.default
    val memoryStream = new ByteArrayOutputStream()
    val newOut = new PrintStream(memoryStream)
    val cliOptions = defaultCli.copy(common = defaultCli.common.copy(out = newOut))
    val action = Run(Commands.Projects(dotGraph = true, cliOptions), Exit(ExitStatus.Ok))
    Interpreter.execute(action, state)

    newOut.flush() // Flush contents just in case
    val dotGraph = memoryStream.toString("UTF-8")
    val graph = Parser.read(dotGraph)
    assert(graph.isDirected, "Dot graph for sbt is not directed")
    ()
  }
}
