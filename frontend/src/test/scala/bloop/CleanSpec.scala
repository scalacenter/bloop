package bloop

import bloop.cli.Commands
import bloop.data.Project
import bloop.engine.Run
import bloop.engine.State
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

import org.junit.Assert
import org.junit.Test

class CleanSpec {
  /*
   *    I
   *    |\
   *    | \
   *    H  G
   *    |
   *    F
   */
  private val structure = Map(
    "F" -> Map("F.scala" -> "package p0\nclass F"),
    "H" -> Map("H.scala" -> "package p1\nclass H"),
    "G" -> Map("G.scala" -> "package p4\nclass G"),
    "I" -> Map("I.scala" -> "package p3\nclass I")
  )
  private val deps = Map("I" -> Set("H", "G", "F"), "H" -> Set("F"))

  private def hasResult(name: String, state: State): Boolean =
    TestUtil.hasPreviousResult(project(name, state), state)

  private def project(name: String, state: State): Project =
    state.build.getProjectFor(name).getOrElse(sys.error(s"Missing project $name"))

  @Test
  def cleanCascadesToDependents(): Unit = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    TestUtil.testState(structure, deps, userLogger = Some(logger)) { state =>
      // Compiling `I` compiles the whole build (I -> H, G, F).
      val compiled = TestUtil.blockingExecute(Run(Commands.Compile(List("I"))), state)
      Assert.assertTrue("Unexpected compilation error", compiled.status.isOk)
      List("F", "H", "G", "I").foreach { p =>
        Assert.assertTrue(s"$p should have compiled", hasResult(p, compiled))
      }

      // Cleaning `F` with `--cascade` must also clean its dependents `H` and `I`, but not `G`.
      val cleaned =
        TestUtil.blockingExecute(Run(Commands.Clean(List("F"), cascade = true)), compiled)
      Assert.assertTrue("Unexpected clean error", cleaned.status.isOk)
      Assert.assertFalse("F should be cleaned", hasResult("F", cleaned))
      Assert.assertFalse("H should be cleaned (depends on F)", hasResult("H", cleaned))
      Assert.assertFalse("I should be cleaned (depends on F)", hasResult("I", cleaned))
      Assert.assertTrue("G should NOT be cleaned", hasResult("G", cleaned))
    }
  }

  @Test
  def cleanIncludesDependencies(): Unit = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    TestUtil.testState(structure, deps, userLogger = Some(logger)) { state =>
      val compiled = TestUtil.blockingExecute(Run(Commands.Compile(List("I"))), state)
      Assert.assertTrue("Unexpected compilation error", compiled.status.isOk)

      // Cleaning `H` with `--include-dependencies` must also clean its dependency `F`,
      // but not its dependent `I` nor the unrelated `G`.
      val cleaned =
        TestUtil.blockingExecute(
          Run(Commands.Clean(List("H"), includeDependencies = true)),
          compiled
        )
      Assert.assertTrue("Unexpected clean error", cleaned.status.isOk)
      Assert.assertFalse("H should be cleaned", hasResult("H", cleaned))
      Assert.assertFalse("F should be cleaned (dependency of H)", hasResult("F", cleaned))
      Assert.assertTrue("I should NOT be cleaned", hasResult("I", cleaned))
      Assert.assertTrue("G should NOT be cleaned", hasResult("G", cleaned))
    }
  }
}
