/*package bloop.engine

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, InputStreamReader, OutputStream, PipedInputStream, PipedOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import bloop.ScalaInstance
import bloop.tasks.ProjectHelpers.{RootProject, checkAfterCleanCompilation}
import bloop.cli.{CliOptions, Commands, CommonOptions}
import bloop.io.AbsolutePath
import org.junit.Test
import bloop.tasks.ProjectHelpers

class ConsoleSpec {
  object ArtificialSources {
    val `A.scala` = "package p0\ntrait A"
    val `B.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
  }

  private val LatestScala = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.4")

  def redirect(common: CommonOptions): (CliOptions, OutputStream, ByteArrayOutputStream) = {
    val outMemory = new ByteArrayOutputStream()
    val newOut = new PrintStream(outMemory)
    val newIn = new PipedInputStream()
    val testOut = new PipedOutputStream(newIn)
    val defaultCli = CliOptions.default
    val cliOptions = defaultCli.copy(common = common.copy(in = newIn, out = newOut))
    (cliOptions, testOut, outMemory)
  }

  @Test
  def RunSimpleConsole(): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))
    checkAfterCleanCompilation(structures, dependencies, LatestScala) { (state: State) =>
      ProjectHelpers.withTemporaryFile { file =>
        val (cliOptions0, testOut, out) = redirect(state.commonOptions)
        val cliOptions = cliOptions0.copy(verbose = true)
        val action = Run(Commands.Console(RootProject, cliOptions = cliOptions))

        val oldIn = Console.in
        val oldOut = Console.out
        val oldErr = Console.err
        try {
          Console.setIn(new InputStreamReader(new ByteArrayInputStream(Array())))
          Console.setOut(cliOptions.common.out)
          Console.setOut(cliOptions.common.err)
          println(s"Console before running ${Console.in}")

          val parallelThread =
            new Thread {
              override def run(): Unit = {
                System.out.println(s" Console within thread ${Console.in}");
                Interpreter.execute(action, state);
                ()
              }
            }
          parallelThread.start()

          Thread.sleep(1000)
          Console.in
          testOut.write("new p0.A".getBytes(StandardCharsets.UTF_8))
          testOut.flush()
          Thread.sleep(2000)
          println(out.toString(StandardCharsets.UTF_8.displayName()))
        } finally {
          println(s"Console after running ${Console.in}")
          Console.setIn(oldIn)
          Console.setOut(oldOut)
          Console.setErr(oldOut)
        }
      }
    }
  }
}*/
