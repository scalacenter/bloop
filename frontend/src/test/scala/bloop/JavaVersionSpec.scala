package bloop

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil
import bloop.util.CrossPlatform

object JavaVersionSpec extends bloop.testing.BaseSuite {

  private val jvmManager = coursierapi.JvmManager.create()

  def checkFlag(
      scalacOpts: List[String],
      jdkVersion: String = "8",
      shouldFail: Boolean = false,
      scalaVersion: Option[String] = None
  ) = {
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
        TestProject(
          workspace,
          "a",
          sources,
          jvmConfig = jvmConfig,
          scalacOptions = scalacOpts,
          scalaVersion = scalaVersion
        )
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      if (jdkVersion == "8" || shouldFail) {
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
      // no release flag available in 2.11
      checkFlag(Nil, jdkVersion = "11", scalaVersion = Some("2.11.12"))
    }
  }

  test("compiles-with-11") {
    checkFlag(Nil, jdkVersion = "11")
  }

  test("doesnt-compile-with-11") {
    checkFlag(List("-release", "8"), jdkVersion = "11", shouldFail = true)
    checkFlag(List("-release:8"), jdkVersion = "11", shouldFail = true)
  }

  test("compiles-with-17") {
    checkFlag(Nil, jdkVersion = "17")
  }

  test("doesnt-compile-with-17") {
    checkFlag(List("-release", "8"), jdkVersion = "17", shouldFail = true)
    checkFlag(List("-release:8"), jdkVersion = "17", shouldFail = true)
  }

  def checkRtJar(jdkVersion: String, scalacOpts: List[String] = Nil) = {
    val javaHome = jvmManager.get(jdkVersion).toPath()
    val jvmConfig = Some(Config.JvmConfig(Some(javaHome), Nil))
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |import java.net.http.HttpClient
          |class Foo{
          |  val output: HttpClient = ???
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
          s"""|[E2] $targetFoo:3:15
              |     not found: type HttpClient
              |     L3:   val output: HttpClient = ???
              |                       ^
              |[E1] $targetFoo:1:17
              |     object http is not a member of package java.net
              |     L1: import java.net.http.HttpClient
              |                         ^
              |$targetFoo: L1 [E1], L3 [E2]
              |Failed to compile 'a'
              |""".stripMargin
        )
      } else {
        assertExitStatus(compiledState, ExitStatus.Ok)
      }
    }
  }

  if (!CrossPlatform.isM1) {
    test("doesnt-compile") {
      checkRtJar(jdkVersion = "8")
    }
  }

  test("compiles-11") {
    checkRtJar(jdkVersion = "11")

  }

  test("compiles-17") {
    checkRtJar(jdkVersion = "17")
  }

  /* Current limitation: this should fail but the generated rt.jar contains all JDK 11 classes.
   * However, most tools generating json configuration will set proper javaHome with JDK 8.
   * Only if a user sets the flags themselves this will wrongly compile classes coming
   * from future JDKs (but not added APIs).
   */
  test("compiles-wrongly") {
    checkRtJar(jdkVersion = "11", List("-release", "8"))
    checkRtJar(jdkVersion = "17", List("-release", "8"))
  }
}
