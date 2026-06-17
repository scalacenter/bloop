package bloop

import bloop.cli.Commands
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.data.Platform
import bloop.engine.Interpreter
import bloop.engine.Run
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

import org.junit.Assert
import org.junit.Test

class GenericTestSpec {
  @Test
  def canTestWithCascading(): Unit = {
    /*
     *    I
     *    |\
     *    | \
     *    H  G
     *    |
     *    F
     *
     *  where:
     *    1. `F` defines no tests
     *    2. `H`, `I` and `G` defines tests
     */

    object Sources {
      val `F.scala` =
        """
          |package p0
          |class F
        """.stripMargin

      val `H.scala` =
        """
          |package p1
          |
          |import org.junit.Test
          |
          |class H extends p0.F {
          |  @Test def testH(): Unit = ()
          |}
        """.stripMargin

      val `G.scala` =
        """
          |package p4
          |
          |import org.junit.Test
          |
          |class G {
          |  @Test def testG(): Unit = ()
          |}
          |
        """.stripMargin

      val `I.scala` =
        """
          |package p3
          |
          |import org.junit.Test
          |import p1.{H => _}
          |
          |class I {
          |  @Test def testI(): Unit = ()
          |}
        """.stripMargin
    }

    val structure = Map(
      "F" -> Map("F.scala" -> Sources.`F.scala`),
      "H" -> Map("H.scala" -> Sources.`H.scala`),
      "I" -> Map("I.scala" -> Sources.`I.scala`),
      "G" -> Map("G.scala" -> Sources.`G.scala`)
    )

    val logger = new RecordingLogger(ansiCodesSupported = false)
    val deps = Map("I" -> Set("H", "G", "F"), "H" -> Set("F"))
    val testProjects = Set("F", "G", "H", "I")
    val junitJars = bloop.internal.build.BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray
    TestUtil.testState(
      structure,
      deps,
      userLogger = Some(logger),
      extraJars = junitJars,
      testProjects = testProjects
    ) { state =>
      val action = Run(Commands.Test(List("F"), cascade = true, args = List("-v", "-a")))
      val compiledState = TestUtil.blockingExecute(action, state)
      Assert.assertTrue("Unexpected compilation error", compiledState.status.isOk)
      // Only expect tests for H and I, cascading should not run tests for G!
      TestUtil.assertNoDiff(
        """
          |Test p1.H.testH started
          |Test p3.I.testI started
          |Test run p1.H started
          |Test run p3.I started
        """.stripMargin,
        logger.startedTestInfos.sorted.mkString(lineSeparator)
      )
    }
  }

  @Test
  def canCombineCascadingAndDependencies(): Unit = {
    /*
     *    I
     *    |\
     *    | \
     *    H  G
     *    |
     *    F
     *
     *  where `F`, `H`, `G` and `I` all define tests.
     *
     *  Testing `H` with both `--cascade` and `--include-dependencies` should run tests for:
     *    - `H` itself
     *    - `I` (cascades up: `I` depends on `H`)
     *    - `F` (includes dependencies: `H` depends on `F`)
     *  but NOT `G`, which is neither a dependent nor a dependency of `H`.
     */

    object Sources {
      val `F.scala` =
        """
          |package p0
          |
          |import org.junit.Test
          |
          |class F {
          |  @Test def testF(): Unit = ()
          |}
        """.stripMargin

      val `H.scala` =
        """
          |package p1
          |
          |import org.junit.Test
          |
          |class H {
          |  @Test def testH(): Unit = ()
          |}
        """.stripMargin

      val `G.scala` =
        """
          |package p4
          |
          |import org.junit.Test
          |
          |class G {
          |  @Test def testG(): Unit = ()
          |}
        """.stripMargin

      val `I.scala` =
        """
          |package p3
          |
          |import org.junit.Test
          |
          |class I {
          |  @Test def testI(): Unit = ()
          |}
        """.stripMargin
    }

    val structure = Map(
      "F" -> Map("F.scala" -> Sources.`F.scala`),
      "H" -> Map("H.scala" -> Sources.`H.scala`),
      "I" -> Map("I.scala" -> Sources.`I.scala`),
      "G" -> Map("G.scala" -> Sources.`G.scala`)
    )

    val logger = new RecordingLogger(ansiCodesSupported = false)
    val deps = Map("I" -> Set("H", "G", "F"), "H" -> Set("F"))
    val testProjects = Set("F", "G", "H", "I")
    val junitJars = bloop.internal.build.BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray
    TestUtil.testState(
      structure,
      deps,
      userLogger = Some(logger),
      extraJars = junitJars,
      testProjects = testProjects
    ) { state =>
      val action = Run(
        Commands.Test(
          List("H"),
          cascade = true,
          includeDependencies = true,
          args = List("-v", "-a")
        )
      )
      val compiledState = TestUtil.blockingExecute(action, state)
      Assert.assertTrue("Unexpected compilation error", compiledState.status.isOk)
      // Cascade pulls in I (depends on H); include-dependencies pulls in F (H depends on F).
      TestUtil.assertNoDiff(
        """
          |Test p0.F.testF started
          |Test p1.H.testH started
          |Test p3.I.testI started
          |Test run p0.F started
          |Test run p1.H started
          |Test run p3.I started
        """.stripMargin,
        logger.startedTestInfos.sorted.mkString(lineSeparator)
      )
    }
  }

  @Test
  def jvmDebugRejectedWhenParallelForksManyJvms(): Unit = {
    // Two test projects + --parallel would fork two JVMs that can't share one debug port.
    object Sources {
      val `F.scala` =
        """
          |package p0
          |import org.junit.Test
          |class F { @Test def testF(): Unit = () }
        """.stripMargin
      val `G.scala` =
        """
          |package p4
          |import org.junit.Test
          |class G { @Test def testG(): Unit = () }
        """.stripMargin
    }
    val structure = Map(
      "F" -> Map("F.scala" -> Sources.`F.scala`),
      "G" -> Map("G.scala" -> Sources.`G.scala`)
    )
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val junitJars = bloop.internal.build.BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray
    TestUtil.testState(
      structure,
      Map.empty[String, Set[String]],
      userLogger = Some(logger),
      extraJars = junitJars,
      testProjects = Set("F", "G")
    ) { state =>
      val action = Run(Commands.Test(List("F", "G"), parallel = true, jvmDebug = Some(5005)))
      val resultState = TestUtil.blockingExecute(action, state)
      Assert.assertEquals(ExitStatus.InvalidCommandLineOption, resultState.status)
      Assert.assertTrue(
        s"Expected an error mentioning --jvm-debug, got: ${logger.errors}",
        logger.errors.exists(_.contains("--jvm-debug"))
      )
    }
  }

  @Test
  def jvmDebugAllowedForSingleProjectEvenWithParallel(): Unit = {
    // A single test project forks one JVM, so --parallel is a no-op and debugging is fine.
    object Sources {
      val `F.scala` =
        """
          |package p0
          |import org.junit.Test
          |class F { @Test def testF(): Unit = () }
        """.stripMargin
    }
    val structure = Map("F" -> Map("F.scala" -> Sources.`F.scala`))
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val junitJars = bloop.internal.build.BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray
    val port = {
      val socket = new java.net.ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }
    TestUtil.testState(
      structure,
      Map.empty[String, Set[String]],
      userLogger = Some(logger),
      extraJars = junitJars,
      testProjects = Set("F")
    ) { state =>
      val action = Run(Commands.Test(List("F"), parallel = true, jvmDebug = Some(port)))
      val resultState = TestUtil.blockingExecute(action, state)
      Assert.assertTrue(
        s"Single-project debug + parallel should not be rejected, got: ${resultState.status}",
        resultState.status.isOk
      )
    }
  }

  @Test
  def debugForkCollisionCountsOnlyJvmTestProjects(): Unit = {
    object Sources {
      val `F.scala` =
        """
          |package p0
          |import org.junit.Test
          |class F { @Test def testF(): Unit = () }
        """.stripMargin
    }
    val structure = Map("F" -> Map("F.scala" -> Sources.`F.scala`))
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val junitJars = bloop.internal.build.BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray
    TestUtil.testState(
      structure,
      Map.empty[String, Set[String]],
      userLogger = Some(logger),
      extraJars = junitJars,
      testProjects = Set("F")
    ) { state =>
      val jvm = state.build.getProjectFor("F").get
      val jvm2 = jvm.copy(name = "F2")
      // A Scala.js test project carries the Test tag but forks no debuggable JVM.
      val js = jvm.copy(name = "Fjs", platform = Platform.Js(Config.JsConfig.empty, None, None))

      // Two JVM test projects would fork two JVMs that can't share one debug port.
      Assert.assertTrue(
        Interpreter.debugForkCollision(Some(5005), parallel = true, List(jvm, jvm2))
      )
      // A JVM test project alongside a Scala.js one: only one JVM forks, so no collision.
      Assert.assertFalse(Interpreter.debugForkCollision(Some(5005), parallel = true, List(jvm, js)))
      // A single JVM test project: `--parallel` is a no-op.
      Assert.assertFalse(Interpreter.debugForkCollision(Some(5005), parallel = true, List(jvm)))
      // No conflict without `--jvm-debug` or without `--parallel`.
      Assert.assertFalse(Interpreter.debugForkCollision(None, parallel = true, List(jvm, jvm2)))
      Assert.assertFalse(
        Interpreter.debugForkCollision(Some(5005), parallel = false, List(jvm, jvm2))
      )
    }
  }

  @Test
  def warnsAboutJvmDebugOnNonJvmProject(): Unit = {
    val structure = Map("F" -> Map("F.scala" -> "package p0\nclass F"))
    TestUtil.testState(structure, Map.empty[String, Set[String]]) { state =>
      val jvm = state.build.getProjectFor("F").get
      val js = jvm.copy(name = "Fjs", platform = Platform.Js(Config.JsConfig.empty, None, None))

      val jsLog = new RecordingLogger(ansiCodesSupported = false)
      Interpreter.warnIfJvmDebugUnsupported(js, Some(5005), jsLog)
      Assert.assertTrue(
        s"Expected a warning for the non-JVM project, got: ${jsLog.warnings}",
        jsLog.warnings.exists(_.contains("--jvm-debug"))
      )

      val jvmLog = new RecordingLogger(ansiCodesSupported = false)
      Interpreter.warnIfJvmDebugUnsupported(jvm, Some(5005), jvmLog)
      Assert.assertTrue("A JVM project must not warn", jvmLog.warnings.isEmpty)

      val noFlagLog = new RecordingLogger(ansiCodesSupported = false)
      Interpreter.warnIfJvmDebugUnsupported(js, None, noFlagLog)
      Assert.assertTrue("Without --jvm-debug there must be no warning", noFlagLog.warnings.isEmpty)
    }
  }
}
