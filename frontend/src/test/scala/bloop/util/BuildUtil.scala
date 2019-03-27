package bloop.util

import bloop.engine.State
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger

object BuildUtil {
  case class SlowBuild(
      workspace: AbsolutePath,
      macroProject: TestProject,
      userProject: TestProject,
      state: State
  )

  def testSlowBuild(logger: RecordingLogger)(testLogic: SlowBuild => Unit): Unit = {
    TestUtil.withinWorkspace { workspace =>
      val slowMacroProject = TestProject(
        workspace,
        "macros",
        List(
          """/main/scala/SleepMacro.scala
            |package macros
            |
            |import scala.reflect.macros.blackbox.Context
            |import scala.language.experimental.macros
            |
            |object SleepMacro {
            |  def sleep(): Unit = macro sleepImpl
            |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
            |    import c.universe._
            |    // Sleep for 1.5 seconds
            |    Thread.sleep(1500)
            |    reify { () }
            |  }
            |}
            |
            """.stripMargin
        )
      )

      val userProject = TestProject(
        workspace,
        "user",
        List(
          """/main/scala/User.scala
            |package user
            |
            |object User extends App {
            |  macros.SleepMacro.sleep()
            |}
          """.stripMargin,
          """/main/scala/User2.scala
            |package user
            |
            |object User2 extends App {
            |  macros.SleepMacro.sleep()
            |}
          """.stripMargin
        ),
        List(slowMacroProject)
      )

      val configDir = TestProject.populateWorkspace(
        workspace,
        List(slowMacroProject, userProject)
      )

      val state = TestUtil.loadTestProject(configDir.underlying, logger)
      testLogic(SlowBuild(workspace, slowMacroProject, userProject, state))
    }
  }
}
