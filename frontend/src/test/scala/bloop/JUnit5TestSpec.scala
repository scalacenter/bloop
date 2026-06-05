package bloop

import bloop.cli.Commands
import bloop.engine.Run
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

import org.junit.Assert
import org.junit.Test

class JUnit5TestSpec {
  // A class whose only test marker is a *method-level* `@org.junit.jupiter.api.Test`.
  // Bloop's fingerprint discovery cannot find this: the Jupiter adapter reports a fake fingerprint
  // on purpose, so even a fully public class is invisible to fingerprint matching. It is found only
  // via the JUnit Platform launcher (JupiterTestCollector).
  private val jupiterTestSource =
    """
      |package hello
      |
      |import org.junit.jupiter.api.Test
      |import org.junit.jupiter.api.Assertions.assertEquals
      |
      |class JupiterTest {
      |  @Test def addition(): Unit = assertEquals(4, 2 + 2)
      |}
    """.stripMargin

  /**
   * Builds a one-project workspace with the given JUnit 5 stack on the classpath, runs `test`, and
   * asserts the method-level `@Test` was discovered (started) and the run was green. Discovery
   * always uses Bloop's bundled JUnit Platform launcher; the run uses whatever engine `jars`
   * provides, so this exercises the discovery→run handoff for both Jupiter 6.x and 5.x.
   */
  private def assertDiscoversAndRuns(jars: Array[AbsolutePath]): Unit = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val structure = Map("test-project" -> Map("JupiterTest.scala" -> jupiterTestSource))
    TestUtil.testState(
      structure,
      Map.empty[String, Set[String]],
      userLogger = Some(logger),
      extraJars = jars,
      testProjects = Set("test-project")
    ) { state =>
      val action = Run(Commands.Test(List("test-project"), args = List("-v", "-a")))
      val testedState = TestUtil.blockingExecute(action, state)
      Assert.assertTrue(
        s"Unexpected compilation/test error:\n${logger.getMessagesAt(Some("error")).mkString(lineSeparator)}",
        testedState.status.isOk
      )
      Assert.assertTrue(
        s"JUnit 5 test was not discovered/run. Started:\n${logger.startedTestInfos.mkString(lineSeparator)}",
        logger.startedTestInfos.exists(_.contains("hello.JupiterTest"))
      )
    }
  }

  @Test
  def canDiscoverAndRunJupiterTests(): Unit = {
    // The bundled adapter version (Jupiter 6.x), resolved on Bloop's own test classpath.
    val jars = bloop.internal.build.BuildTestInfo.jupiterTestJars.map(AbsolutePath.apply).toArray
    assertDiscoversAndRuns(jars)
  }

  @Test
  def canDiscoverAndRunJupiter5xTests(): Unit = {
    // Run a project on the JUnit Platform 1.x / Jupiter 5.x stack (jupiter-interface 0.16.0) while
    // Bloop discovers it with its bundled 6.x launcher — proving the bundle supports 5.x projects.
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val jars = DependencyResolution.resolve(
      List(DependencyResolution.Artifact("com.github.sbt.junit", "jupiter-interface", "0.16.0")),
      logger
    )
    assertDiscoversAndRuns(jars)
  }
}
