package bloop

import bloop.cli.ExitStatus
import bloop.cli.ScalacRepl
import bloop.config.Config
import bloop.engine.Interpreter
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

  // The server logs the REPL command newline-joined; split it back into its tokens. The first
  // token is the JDK's java binary (an absolute path), so we locate the command by its `-cp` token.
  private def replCommandTokens(logger: RecordingLogger): IndexedSeq[String] =
    logger.infos
      .map(_.split("\n").toIndexedSeq)
      .find(_.contains("-cp"))
      .getOrElse(sys.error(s"No REPL command logged: ${logger.infos}"))

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
      assert(tokens.head.endsWith("java") || tokens.head.endsWith("java.exe"))
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

  test("scalaReplArtifactAndMain selects the REPL artifacts per Scala version") {
    def select(version: String): (List[String], String) = {
      val (artifacts, mainClass) = Interpreter.scalaReplArtifactAndMain(version)
      artifacts.foreach { a =>
        assertEquals(a.organization, "org.scala-lang")
        assertEquals(a.version, version)
      }
      (artifacts.map(_.module), mainClass)
    }
    val scala2 = (List("scala-compiler"), "scala.tools.nsc.MainGenericRunner")
    val scala3Old = (List("scala3-compiler_3"), "dotty.tools.repl.Main")
    // scala3-repl excludes the stdlib, so scala3-compiler must be resolved alongside it.
    val scala3New = (List("scala3-repl_3", "scala3-compiler_3"), "dotty.tools.repl.Main")
    assertEquals(select("2.13.18"), scala2)
    assertEquals(select("2.12.21"), scala2)
    assertEquals(select("3.3.4"), scala3Old)
    assertEquals(select("3.7.4"), scala3Old)
    assertEquals(select("3.8.0"), scala3New)
    assertEquals(select("3.10.0"), scala3New)
    // Suffixed (RC/nightly) versions compare on the numeric part only.
    assertEquals(select("3.8.0-RC1"), scala3New)
    assertEquals(select("3.7.4-RC1-bin-20260101-abcdef-NIGHTLY"), scala3Old)
  }

  consoleTest("excludeRoot console command uses only the dependencies' classpath") {
    TestUtil.withinWorkspace { workspace =>
      val aSource = """/A.scala
                      |class A
                    """.stripMargin
      val bSource = """/B.scala
                      |class B extends A
                    """.stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(aSource))
      val `B` = TestProject(workspace, "b", List(bSource), List(`A`))
      val state = loadState(workspace, List(`A`, `B`), logger)

      val consoleState = state.console(`B`, List("--no-home-predef"), excludeRoot = true)
      assert(consoleState.status == ExitStatus.Ok)

      val classpath = {
        val tokens = replCommandTokens(logger)
        tokens(tokens.indexOf("-cp") + 1)
      }
      val projectA = state.getProjectFor(`A`)
      val projectB = state.getProjectFor(`B`)
      val classpathA =
        projectA.fullRuntimeClasspath(state.getDagFor(`A`), state.client).map(_.syntax).toList
      // a's classpath must be present; b's own entries (those not in a's classpath) must not be.
      classpathA.foreach(entry => assertContains(classpath, entry))
      projectB
        .fullRuntimeClasspath(state.getDagFor(`B`), state.client)
        .map(_.syntax)
        .filterNot(classpathA.contains)
        .foreach(bOwn => assert(!classpath.contains(bOwn)))
    }
  }

  // The scalac REPL must also initialize on JDK > 8 (via -Dscala.usejavacp=true) without the
  // MissingRequirementError, with the project's classes visible in the session.
  consoleTest("scalac console launches without MissingRequirementError") {
    TestUtil.withinWorkspace { workspace =>
      val marker = "bloop-scalac-ok"
      val aSource =
        s"""/A.scala
           |package mytest
           |object A { val marker = "$marker" }
          """.stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(aSource))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      val consoleState = state.console(`A`, args = Nil, repl = ScalacRepl)
      assert(consoleState.status == ExitStatus.Ok)

      val tokens = replCommandTokens(logger)
      assert(tokens.contains("-Dscala.usejavacp=true"))

      // The scalac REPL reads from stdin; feed it an expression using the project class, then quit.
      val replInput = s"""println(mytest.A.marker)${lineSeparator}:quit$lineSeparator"""
      val input =
        new java.io.ByteArrayInputStream(
          replInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
      val output = new StringBuilder
      val procLogger = scala.sys.process.ProcessLogger { line =>
        output.append(line).append(lineSeparator)
        ()
      }
      (scala.sys.process.Process(tokens, workspace.toFile) #< input).!(procLogger)

      Predef.assert(
        !output.toString.contains("MissingRequirementError"),
        s"Scala REPL failed with MissingRequirementError:\n$output"
      )
      // Printing the marker proves the REPL started and the project is on the session classpath.
      Predef.assert(
        output.toString.contains(marker),
        s"Expected '$marker' (from the project class) in Scala REPL output:\n$output"
      )
    }
  }

  // A missing configured JDK must be reported by the server (like `run`), not emitted as a
  // command that only fails later in the local CLI. No coursier needed: the guard runs first.
  test("console reports a missing configured JDK") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val missingJdk = workspace.resolve("no-such-jdk")
      val `A` = TestProject(
        workspace,
        "a",
        List("""/A.scala
               |class A
             """.stripMargin),
        runtimeJvmConfig = Some(Config.JvmConfig(Some(missingJdk.underlying), Nil))
      )
      val state = loadState(workspace, List(`A`), logger)

      val consoleState = state.console(`A`, Nil)
      assert(consoleState.status == ExitStatus.RunError)
      assertContains(
        logger.errors.mkString(lineSeparator),
        "Configured Java executable does not exist"
      )
    }
  }
}
