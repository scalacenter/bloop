package bloop

import bloop.cli.CliOptions
import bloop.cli.Commands
import bloop.cli.completion
import bloop.engine.Run
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

import org.junit.Test

class AutoCompleteSpec {

  @Test
  def zshCompletions: Unit =
    check(
      completion.ZshFormat,
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
        |--projects[The projects to compile (will be inferred from remaining cli args).]:projects:
        |--incremental=-[Compile the project incrementally. By default, true.]:incremental:(true false)
        |--pipeline=-[Pipeline the compilation of modules in your build. By default, false.]:pipeline:(true false)
        |--reporter[Pick reporter to show compilation messages. By default, bloop's used.]:reporter:_reporters
        |--watch=-[Run the command when projects' source files change. By default, false.]:watch:(true false)
        |--cascade=-[Compile a project and all projects depending on it. By default, false.]:cascade:(true false)
        |--config-dir[File path to the bloop config directory, defaults to `.bloop` in the current working directory.]:config-dir:
        |--version=-[If set, print the about section at the beginning of the execution. Defaults to false.]:version:(true false)
        |--verbose=-[If set, print out debugging information to stderr. Defaults to false.]:verbose:(true false)
        |--no-color=-[If set, do not color output. Defaults to false.]:no-color:(true false)
        |--debug[Debug the execution of a concrete task.]:debug:
        """.stripMargin
    )

  @Test
  def fishCompletions: Unit =
    check(
      completion.FishFormat,
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
        |projects#'The projects to compile (will be inferred from remaining cli args).'#
        |incremental#'Compile the project incrementally. By default, true.'#(_boolean)
        |pipeline#'Pipeline the compilation of modules in your build. By default, false.'#(_boolean)
        |reporter#'Pick reporter to show compilation messages. By default, bloop's used.'#(_reporters)
        |watch#'Run the command when projects' source files change. By default, false.'#(_boolean)
        |cascade#'Compile a project and all projects depending on it. By default, false.'#(_boolean)
        |config-dir#'File path to the bloop config directory, defaults to `.bloop` in the current working directory.'#
        |version#'If set, print the about section at the beginning of the execution. Defaults to false.'#(_boolean)
        |verbose#'If set, print out debugging information to stderr. Defaults to false.'#(_boolean)
        |no-color#'If set, do not color output. Defaults to false.'#(_boolean)
        |debug#'Debug the execution of a concrete task.'#
      """.stripMargin
    )

  @Test
  def bashCompletions: Unit =
    check(
      completion.BashFormat,
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
        |--projects
        |--incremental
        |--pipeline
        |--reporter _reporters
        |--watch
        |--cascade
        |--config-dir
        |--version
        |--verbose
        |--no-color
        |--debug
        """.stripMargin
    )

  def check(format: completion.Format, expected: String): Unit = {
    val structure = Map("A" -> Map[String, String](), "B" -> Map[String, String]())
    val deps = Map("B" -> Set("A"))
    val logger = new RecordingLogger(ansiCodesSupported = false)

    TestUtil.testState(structure, deps, userLogger = Some(logger)) { state0 =>
      val cliOptions = CliOptions.default.copy(common = state0.commonOptions)
      val test1 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.Commands,
        format,
        None,
        None
      )

      val test2 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.ProjectBoundCommands,
        format,
        None,
        None
      )

      // Simulate basic 'bloop compile <autocomplete>'
      val test3 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.Projects,
        format,
        Some("compile"),
        None
      )

      // Simulate 'bloop compile A <autocomplete>', happens when we want to add multiple projects
      val test4 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.Projects,
        format,
        Some("compile"),
        Some("A")
      )

      val test5 = Commands.Autocomplete(
        cliOptions,
        completion.Mode.Flags,
        format,
        Some("compile"),
        Some("A")
      )

      val action = Run(test1, Run(test2, Run(test3, Run(test4, Run(test5)))))
      val state1 = TestUtil.blockingExecute(action, state0)
      TestUtil.assertNoDiff(
        expected,
        logger.infos.mkString(lineSeparator)
      )
    }
  }
}
