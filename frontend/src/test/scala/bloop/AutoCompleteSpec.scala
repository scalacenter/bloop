package bloop

import bloop.cli.{CliOptions, Commands, completion}
import bloop.engine.Run
import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import org.junit.Test

class AutoCompleteSpec {
  @Test
  def testAutoCompletionForProjects: Unit = {
    val structure = Map("A" -> Map[String, String](), "B" -> Map[String, String]())
    val deps = Map("B" -> Set("A"))
    val logger = new RecordingLogger(ansiCodesSupported = false)

    TestUtil.testState(structure, deps, userLogger = Some(logger)) { state0 =>
      val cliOptions = CliOptions.default.copy(common = state0.commonOptions)
      val test1 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.Commands,
        completion.ZshFormat,
        None,
        None
      )

      val test2 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.ProjectBoundCommands,
        completion.ZshFormat,
        None,
        None
      )

      // Simulate basic 'bloop compile <autocomplete>'
      val test3 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.Projects,
        completion.ZshFormat,
        Some("compile"),
        None
      )

      // Simulate 'bloop compile A <autocomplete>', happens when we want to add multiple projects
      val test4 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.Projects,
        completion.ZshFormat,
        Some("compile"),
        Some("A")
      )

      val action = Run(test1, Run(test2, Run(test3, Run(test4))))
      val state1 = TestUtil.blockingExecute(action, state0)
      TestUtil.assertNoDiff(
        """
          |about
          |autocomplete
          |bsp
          |clean
          |compile
          |configure
          |console
          |help
          |link
          |projects
          |run
          |test
          |clean compile console link run test
          |A
          |B
          |A
          |B
        """.stripMargin,
        logger.infos.mkString(System.lineSeparator)
      )
    }
  }
}
