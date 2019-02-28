package bloop

import org.junit.{Assert, Test}
import org.junit.experimental.categories.Category
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import bloop.logging.RecordingLogger
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Feedback, Run, State}
import bloop.util.{TestProject, TestUtil, BuildUtil}

@Category(Array(classOf[bloop.FastTests]))
class DiagnosticsSpec {
  @Test
  def printRangePositionsInDiagnostics(): Unit = {
    TestUtil.withinWorkspace { baseDir =>
      val project = TestProject(
        baseDir,
        "ticket-787",
        List(
          """/main/scala/A.scala
            |object A {
            |  "".lengthCompare("1".substring(0))
            |
            |  // Make range pos multi-line to ensure range pos doesn't work here
            |  "".lengthCompare("1".
            |    substring(0))
            |}""".stripMargin
        ),
        scalacOptions = List("-Yrangepos")
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A.scala` = project.srcFor("main/scala/A.scala")
      val state = TestUtil.loadStateFromProjects(baseDir, List(project)).copy(logger = logger)
      val action = Run(Commands.Compile(List("ticket-787")))
      val compiledState = TestUtil.blockingExecute(action, state)
      TestUtil.assertNoDiff(
        s"""
           |[E2] ${TestUtil.universalPath("ticket-787/src/main/scala/A.scala")}:6:14
           |     type mismatch;
           |      found   : String
           |      required: Int
           |     L6:     substring(0))
           |                      ^
           |[E1] ${TestUtil.universalPath("ticket-787/src/main/scala/A.scala")}:2:33
           |     type mismatch;
           |      found   : String
           |      required: Int
           |     L2:   "".lengthCompare("1".substring(0))
           |                            ^^^^^^^^^^^^^^^^
           |${TestUtil.universalPath("ticket-787/src/main/scala/A.scala")}: L2 [E1], L6 [E2]
           |'ticket-787' failed to compile.""".stripMargin,
        logger.errors.mkString(System.lineSeparator())
      )

      ()
    }
  }

  @Test
  def avoidAggressiveDiagnosticDeduplication(): Unit = {
    object Sources {
      val `A.scala` =
        """object Dep {
          |  val a1: Int = ""
          |  val a2: Int = ""
          |}""".stripMargin
    }

    val deps = Map.empty[String, Set[String]]
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val structure = Map(TestUtil.RootProject -> Map("A.scala" -> Sources.`A.scala`))
    TestUtil.checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) {
      (state: State) =>
        assertEquals(logger.errors.size.toLong, 4.toLong)
    }
  }
}
