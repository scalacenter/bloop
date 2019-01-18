package bloop.util

import bloop.engine.State
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger

object BuildUtil {
  case class SlowBuild(macroProject: TestProject, userProject: TestProject, state: State)
  def testSlowBuild(logger: RecordingLogger)(testLogic: SlowBuild => Unit): Unit = {
    TestUtil.withinWorkspace { workspace =>
      val scalaJars = TestUtil.scalaInstance.allJars.map(AbsolutePath.apply)
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
            |    // Sleep for 3 seconds to give time to cancel compilation
            |    Thread.sleep(3000)
            |    reify { () }
            |  }
            |}
            |
        """.stripMargin
        ),
        jars = scalaJars
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
        """.stripMargin
        ),
        List(slowMacroProject.config.name),
        jars = scalaJars
      )

      val configDir = TestProject.populateWorkspace(
        workspace,
        List(slowMacroProject, userProject)
      )

      val state = TestUtil.loadTestProject(configDir.underlying, identity(_)).copy(logger = logger)
      val build = SlowBuild(slowMacroProject, userProject, state)
      testLogic(build)
    }
  }
}
