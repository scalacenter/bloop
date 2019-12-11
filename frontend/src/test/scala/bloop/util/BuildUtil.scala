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

  def testSlowAndReallyLargeBuild(logger: RecordingLogger)(testLogic: SlowBuild => Unit): Unit = {
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
            |  println(user.Foo1.greeting)
            |  println(user.Foo2.greeting)
            |  println(user.Foo3.greeting)
            |  println(user.Foo4.greeting)
            |  println(user.Foo5.greeting)
            |  println(user.Foo6.greeting)
            |  println(user.Foo7.greeting)
            |  println(user.Foo8.greeting)
            |  println(user.Foo9.greeting)
            |  println(user.Foo10.greeting)
            |  println(user.Foo11.greeting)
            |  println(user.Foo12.greeting)
            |  println(user.Foo13.greeting)
            |  println(user.Foo14.greeting)
            |  println(user.Foo15.greeting)
            |  println(user.Foo16.greeting)
            |  println(user.Foo17.greeting)
            |  println(user.Foo18.greeting)
            |  println(user.Foo19.greeting)
            |  println(user.Foo20.greeting)
            |  println(user.Foo21.greeting)
            |  println(user.Foo22.greeting)
            |  println(user.Foo23.greeting)
            |  println(user.Foo24.greeting)
            |  println(user.Foo25.greeting)
            |  println(user.Foo26.greeting)
            |  println(user.Foo27.greeting)
            |  println(user.Foo28.greeting)
            |  println(user.Foo29.greeting)
            |  println(user.Foo30.greeting)
            |}
          """.stripMargin,
          """/main/scala/User2.scala
            |package user
            |
            |object User2 {
            |  macros.SleepMacro.sleep()
            |}
          """.stripMargin,
          """/main/scala/Foo1.scala
            |package user
            |
            |object Foo1 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo2.scala
            |package user
            |
            |object Foo2 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo3.scala
            |package user
            |
            |object Foo3 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo4.scala
            |package user
            |
            |object Foo4 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo5.scala
            |package user
            |
            |object Foo5 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo6.scala
            |package user
            |
            |object Foo6 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo7.scala
            |package user
            |
            |object Foo7 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo8.scala
            |package user
            |
            |object Foo8 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo9.scala
            |package user
            |
            |object Foo9 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo10.scala
            |package user
            |
            |object Foo10 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo11.scala
            |package user
            |
            |object Foo11 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo12.scala
            |package user
            |
            |object Foo12 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo13.scala
            |package user
            |
            |object Foo13 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo14.scala
            |package user
            |
            |object Foo14 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo15.scala
            |package user
            |
            |object Foo15 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo16.scala
            |package user
            |
            |object Foo16 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo17.scala
            |package user
            |
            |object Foo17 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo18.scala
            |package user
            |
            |object Foo18 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo19.scala
            |package user
            |
            |object Foo19 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo20.scala
            |package user
            |
            |object Foo20 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo21.scala
            |package user
            |
            |object Foo21 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo22.scala
            |package user
            |
            |object Foo22 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo23.scala
            |package user
            |
            |object Foo23 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo24.scala
            |package user
            |
            |object Foo24 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo25.scala
            |package user
            |
            |object Foo25 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo26.scala
            |package user
            |
            |object Foo26 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo27.scala
            |package user
            |
            |object Foo27 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo28.scala
            |package user
            |
            |object Foo28 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo29.scala
            |package user
            |
            |object Foo29 {
            |  val greeting = "Hello, World!"
            |}
          """.stripMargin,
          """/main/scala/Foo30.scala
            |package user
            |
            |object Foo30 {
            |  val greeting = "Hello, World!"
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
