package bloop

import bloop.cli.Commands
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import bloop.engine.Run
import org.junit.{Assert, Test}

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
    val junitJars = bloop.internal.build.BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray
    TestUtil.testState(structure, deps, userLogger = Some(logger), extraJars = junitJars) { state =>
      val action = Run(Commands.Test(List("F"), cascade = true, args = List("-v", "-a")))
      val compiledState = TestUtil.blockingExecute(action, state)
      Assert.assertTrue("Unexpected compilation error", compiledState.status.isOk)
      // Only expect tests for H and I, cascading should not run tests for G!
      TestUtil.assertNoDiff(
        """
          |Test p1.H.testH started
          |Test p3.I.testI started
          |Test run started
          |Test run started
        """.stripMargin,
        logger.startedTestInfos.sorted.mkString(System.lineSeparator)
      )
    }
  }
}
