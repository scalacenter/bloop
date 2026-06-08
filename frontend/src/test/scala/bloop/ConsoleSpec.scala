package bloop

import bloop.cli.ExitStatus
import bloop.internal.build.BuildInfo
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.testing.BaseSuite
import bloop.util.TestProject
import bloop.util.TestUtil

object ConsoleSpec extends BaseSuite {
  test("default ammonite console works in multi-build project") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A
          """.stripMargin
        val `B.scala` =
          """/B.scala
            |class B extends A
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val ammArgs = List("--no-home-predef")
      val compiledState = state.console(`B`, ammArgs)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val projectB = state.getProjectFor(`B`)
      val dagB = state.getDagFor(`B`)
      val classpathB = projectB.fullRuntimeClasspath(dagB, state.client)
      val coursierClasspathArgs =
        classpathB.flatMap(elem => Seq("--extra-jars", elem.syntax))
      val expectedCommand =
        s"coursier launch com.lihaoyi:ammonite_${BuildInfo.scalaVersion}:latest.release --main-class ammonite.Main ${coursierClasspathArgs
            .mkString(" ")} ${("--" :: ammArgs).mkString(" ")}"

      assertNoDiff(
        logger.captureTimeInsensitiveInfos
          .filterNot(msg =>
            msg == "" || msg.startsWith("Non-compiled module") || msg
              .startsWith(" Compilation completed in")
          )
          .mkString(lineSeparator),
        s"""|Compiling a (1 Scala source)
            |Compiled a ???
            |Compiling b (1 Scala source)
            |Compiled b ???
            |$expectedCommand
            |""".stripMargin
      )

      // The generated command must use a current Ammonite (`latest.release`, which supports
      // JDK > 8) and must not emit a `--scala-version` flag; together those previously broke
      // the console with a MissingRequirementError on JDK > 8.
      val ammoniteCommand = logger.infos
        .find(_.startsWith("coursier"))
        .getOrElse(sys.error(s"No coursier command logged: ${logger.infos}"))
      assert(ammoniteCommand.contains("latest.release"))
      assert(!ammoniteCommand.contains("--scala-version"))
    }
  }

  // On JDK > 8 the Ammonite console used to fail with
  //   MissingRequirementError: object java.lang.Object in compiler mirror not found
  // Actually launching the command bloop generates must initialize Ammonite's compiler on the
  // running JDK without that error. Skipped when the `coursier` launcher is not on the PATH.
  private val launchTestName =
    "ammonite console launches without MissingRequirementError"
  if (TestUtil.hasCoursier) {
    test(launchTestName) {
      TestUtil.withinWorkspace { workspace =>
        val aSource =
          """/A.scala
            |class A
          """.stripMargin

        val logger = new RecordingLogger(ansiCodesSupported = false)
        val `A` = TestProject(workspace, "a", List(aSource))
        val projects = List(`A`)
        val state = loadState(workspace, projects, logger)

        // Run Ammonite headlessly: evaluate code and exit instead of waiting for a terminal.
        val marker = "bloop-1226-ok"
        val ammArgs = List("--no-home-predef", "-c", s"""println("$marker")""")
        val consoleState = state.console(`A`, ammArgs)
        assert(consoleState.status == ExitStatus.Ok)

        // The server prints the `coursier launch ...` command (newline-joined) as an info message.
        val command = logger.infos
          .find(_.startsWith("coursier"))
          .getOrElse(sys.error(s"No coursier command logged: ${logger.infos}"))
        val tokens = command.split("\n").toIndexedSeq

        val output = new StringBuilder
        val procLogger = scala.sys.process.ProcessLogger { line =>
          output.append(line).append(lineSeparator)
          ()
        }
        val exit = scala.sys.process.Process(tokens, workspace.toFile).!(procLogger)

        Predef.assert(
          !output.toString.contains("MissingRequirementError"),
          s"Ammonite failed with MissingRequirementError:\n$output"
        )
        Predef.assert(exit == 0, s"Ammonite exited with $exit:\n$output")
        Predef.assert(
          output.toString.contains(marker),
          s"Expected '$marker' in Ammonite output:\n$output"
        )
      }
    }
  } else {
    ignore(launchTestName, "IGNORED (coursier not on PATH)")(())
  }
}
