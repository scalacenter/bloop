package bloop.logging

import bloop.Cli
import bloop.cli.{Commands, CommonOptions, ExitStatus}
import bloop.engine.{Exit, Print, Run}
import org.junit.{Assert, Test}

class DebugFilterSpec {
  @Test
  def parseDebugFilterAll(): Unit = {
    val args = Array("compile", "foo", "--debug", "all")
    Cli.parse(args, CommonOptions.default) match {
      case Run(c: Commands.Compile, _) if c.cliOptions.debug == List(DebugFilter.All) => ()
      case r => Assert.fail(s"Expected `Compile` command with debug filter `All`, got $r")
    }
  }

  @Test
  def parseDebugAndSimplifyToAll(): Unit = {
    val args = Array("compile", "foo", "--debug", "all", "--debug", "compilation")
    Cli.parse(args, CommonOptions.default) match {
      case Run(c: Commands.Compile, _) =>
        val uniqueFilter = DebugFilter.toUniqueFilter(c.cliOptions.debug)
        Assert.assertTrue(
          s"Expected only All, got ${uniqueFilter}",
          uniqueFilter == DebugFilter.All
        )
      case r => Assert.fail(s"Expected `Compile` command with debug filter `All`, got $r")
    }
  }

  @Test
  def parseDebugFilters(): Unit = {
    val args = Array("compile", "foo", "--debug", "bsp", "--debug", "compilation")
    Cli.parse(args, CommonOptions.default) match {
      case Run(c: Commands.Compile, _) =>
        DebugFilter.toUniqueFilter(c.cliOptions.debug) match {
          case DebugFilter.Aggregate(obtained) =>
            Assert.assertTrue(
              s"Expected Debug and Bsp, got ${obtained}",
              obtained.toSet == Set(DebugFilter.Bsp, DebugFilter.Compilation)
            )
          case r => Assert.fail(s"Expected aggregate of Bsp and Compilation, got $r")
        }
      case r => Assert.fail(s"Expected `Compile` command with debug filter `All`, got $r")
    }
  }

  @Test
  def parseAllDebugFilters(): Unit = {
    val args = Array(
      "compile",
      "foo",
      "--debug",
      "test",
      "--debug",
      "compilation",
      "--debug",
      "file-watching",
      "--debug",
      "bsp"
    )

    Cli.parse(args, CommonOptions.default) match {
      case Run(c: Commands.Compile, _) =>
        DebugFilter.toUniqueFilter(c.cliOptions.debug) match {
          case DebugFilter.Aggregate(obtained) =>
            Assert.assertTrue(
              s"Expected all debug filters, got ${obtained}",
              obtained.toSet == Set(
                DebugFilter.FileWatching,
                DebugFilter.Test,
                DebugFilter.Bsp,
                DebugFilter.Compilation
              )
            )
          case r => Assert.fail(s"Expected aggregate of Bsp and Compilation, got $r")
        }
      case r => Assert.fail(s"Expected `Compile` command with debug filter `All`, got $r")
    }
  }

  @Test
  def failWhenParsingOnlyDebugHeader(): Unit = {
    val args = Array("compile", "foo", "--debug")
    Cli.parse(args, CommonOptions.default) match {
      case Print(msg, _, Exit(ExitStatus.InvalidCommandLineOption)) if msg == "argument missing" =>
      case r => Assert.fail(s"Expected `Print` with argument missing followed by exit, got $r")
    }
  }

  @Test
  def checkSubsumption(): Unit = {
    def checkTrue(
        check: Boolean
    )(implicit path: sourcecode.Enclosing, line: sourcecode.Line): Unit = {
      Assert.assertTrue(s"Unexpected false at ${path.value}:${line.value}", check)
    }

    checkTrue(DebugFilter.checkSubsumption(DebugFilter.All, DebugFilter.All))
    checkTrue(DebugFilter.checkSubsumption(DebugFilter.All, DebugFilter.Compilation))
    checkTrue(DebugFilter.checkSubsumption(DebugFilter.All, DebugFilter.Test))
    checkTrue(DebugFilter.checkSubsumption(DebugFilter.All, DebugFilter.FileWatching))
    checkTrue(DebugFilter.checkSubsumption(DebugFilter.All, DebugFilter.Bsp))
    checkTrue(
      DebugFilter.checkSubsumption(DebugFilter.All, DebugFilter.Aggregate(List(DebugFilter.All)))
    )

    checkTrue(DebugFilter.checkSubsumption(DebugFilter.Compilation, DebugFilter.All))
    checkTrue(DebugFilter.checkSubsumption(DebugFilter.Test, DebugFilter.All))
    checkTrue(DebugFilter.checkSubsumption(DebugFilter.FileWatching, DebugFilter.All))
    checkTrue(DebugFilter.checkSubsumption(DebugFilter.Bsp, DebugFilter.All))
    checkTrue(
      DebugFilter.checkSubsumption(DebugFilter.Aggregate(List(DebugFilter.All)), DebugFilter.All)
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.All)),
        DebugFilter.Aggregate(List(DebugFilter.All))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.All)),
        DebugFilter.Aggregate(List(DebugFilter.Compilation))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.Compilation)),
        DebugFilter.Aggregate(List(DebugFilter.All))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.All)),
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.Test, DebugFilter.Bsp))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.FileWatching)),
        DebugFilter.Aggregate(List(DebugFilter.All))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.All, DebugFilter.Test, DebugFilter.FileWatching)),
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.Test, DebugFilter.Bsp))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.FileWatching)),
        DebugFilter.Aggregate(List(DebugFilter.All, DebugFilter.Compilation, DebugFilter.Test))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.Test, DebugFilter.FileWatching)),
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.Test, DebugFilter.Bsp))
      )
    )

    checkTrue(
      DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.FileWatching)),
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.Test))
      )
    )

    // Only test a few, could be improved with property testing in the future
    checkTrue(!DebugFilter.checkSubsumption(DebugFilter.Compilation, DebugFilter.Test))
    checkTrue(!DebugFilter.checkSubsumption(DebugFilter.Test, DebugFilter.FileWatching))
    checkTrue(!DebugFilter.checkSubsumption(DebugFilter.Test, DebugFilter.Bsp))
    checkTrue(!DebugFilter.checkSubsumption(DebugFilter.Bsp, DebugFilter.Compilation))

    checkTrue(
      !DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.FileWatching)),
        DebugFilter.Aggregate(List(DebugFilter.Test, DebugFilter.Bsp))
      )
    )

    checkTrue(
      !DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.Compilation)),
        DebugFilter.Aggregate(List(DebugFilter.Test))
      )
    )

    checkTrue(
      !DebugFilter.checkSubsumption(
        DebugFilter.Aggregate(List(DebugFilter.FileWatching)),
        DebugFilter.Aggregate(List(DebugFilter.Compilation, DebugFilter.Test))
      )
    )
  }
}
