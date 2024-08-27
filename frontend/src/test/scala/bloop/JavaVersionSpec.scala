package bloop

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil
import bloop.util.CrossPlatform

object JavaVersionSpec extends bloop.testing.BaseSuite {

  private val jvmManager = coursierapi.JvmManager.create()

  def checkFlag(scalacOpts: List[String], jdkVersion: String = "8") = {
    val javaHome = jvmManager.get(jdkVersion).toPath()
    val jvmConfig = Some(Config.JvmConfig(Some(javaHome), Nil))
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo{
          |  val output = "La ".repeat(2) + "Land";
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` =
        TestProject(workspace, "a", sources, jvmConfig = jvmConfig, scalacOptions = scalacOpts)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      if (jdkVersion == "8") {
        assertExitStatus(compiledState, ExitStatus.CompilationError)
        val targetFoo = TestUtil.universalPath("a/src/main/scala/Foo.scala")
        assertNoDiff(
          logger.renderErrors(),
          s"""|[E1] $targetFoo:2:22
              |     value repeat is not a member of String
              |     L2:   val output = "La ".repeat(2) + "Land";
              |                              ^
              |$targetFoo: L2 [E1]
              |Failed to compile 'a'
              |""".stripMargin
        )
      } else {
        assertExitStatus(compiledState, ExitStatus.Ok)
      }
    }
  }

  if (!CrossPlatform.isM1) {
    test("flag-is-added-correctly") {
      checkFlag(Nil)
    }

    test("flag-is-not-added-correctly") {
      checkFlag(List("-release", "8"))
      checkFlag(List("-release:8"))
    }
  }

  test("compiles-with-11") {
    checkFlag(Nil, jdkVersion = "11")
  }

  test("compiles-with-17") {
    checkFlag(Nil, jdkVersion = "17")
  }
}
