package bloop

import bloop.cli.ExitStatus
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.testing.BaseSuite
import bloop.util.TestProject
import bloop.util.TestUtil

object ConsoleSpec extends BaseSuite {

  // The console resolves REPL artifacts with coursier's API, so these tests need a working
  // coursier setup; gate on the launcher being present as a proxy and skip otherwise.
  private def consoleTest(name: String)(fun: => Any): Unit =
    if (TestUtil.hasCoursier) test(name)(fun)
    else ignore(name, "IGNORED (coursier not on PATH)")(fun)

  // The server logs the REPL command newline-joined; split it back into its tokens.
  private def replCommandTokens(logger: RecordingLogger): IndexedSeq[String] =
    logger.infos
      .find(_.startsWith("java"))
      .getOrElse(sys.error(s"No REPL command logged: ${logger.infos}"))
      .split("\n")
      .toIndexedSeq

  consoleTest("default ammonite console command uses the project classpath") {
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
      val consoleState = state.console(`B`, ammArgs)
      assert(consoleState.status == ExitStatus.Ok)
      assertValidCompilationState(consoleState, projects)

      val projectB = state.getProjectFor(`B`)
      val dagB = state.getDagFor(`B`)
      val classpathB = projectB.fullRuntimeClasspath(dagB, state.client)

      val tokens = replCommandTokens(logger)
      assert(tokens.head == "java")
      assert(tokens.contains("ammonite.Main"))
      // The whole project runtime classpath (a, b and their deps) must be on the REPL classpath.
      val classpath = tokens(tokens.indexOf("-cp") + 1)
      classpathB.foreach(entry => assertContains(classpath, entry.syntax))
      // REPL args are forwarded after the main class.
      assertEquals(tokens.drop(tokens.indexOf("ammonite.Main") + 1).toList, ammArgs)
    }
  }

  // On JDK > 8 the Ammonite console used to fail with
  //   MissingRequirementError: object java.lang.Object in compiler mirror not found
  // Actually launching the command the server generates must initialize Ammonite's compiler on
  // the running JDK without that error, with the project's classes visible in the REPL.
  consoleTest("ammonite console launches without MissingRequirementError") {
    TestUtil.withinWorkspace { workspace =>
      val marker = "bloop-1226-ok"
      val aSource =
        s"""/A.scala
           |package mytest
           |object A { val marker = "$marker" }
          """.stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(aSource))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      // Run Ammonite headlessly: print a value from the project's own class, then exit.
      val ammArgs = List("--no-home-predef", "-c", "println(mytest.A.marker)")
      val consoleState = state.console(`A`, ammArgs)
      assert(consoleState.status == ExitStatus.Ok)

      val tokens = replCommandTokens(logger)
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
      // Printing the marker proves both that Ammonite started and that the project is on the
      // REPL classpath (the old `coursier launch --extra-jars` behaviour).
      Predef.assert(
        output.toString.contains(marker),
        s"Expected '$marker' (from the project class) in Ammonite output:\n$output"
      )
    }
  }
}
