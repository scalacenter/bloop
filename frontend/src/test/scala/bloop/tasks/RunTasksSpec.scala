package bloop.tasks

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import bloop.ScalaInstance
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.experimental.categories.Category
import bloop.cli.Commands
import bloop.engine.{ExecutionContext, Run, State}
import bloop.engine.tasks.Tasks
import bloop.exec.JavaEnv
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil.{checkAfterCleanCompilation, getProject, loadTestProject, runAndCheck}
import monix.execution.misc.NonFatal

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@Category(Array(classOf[bloop.FastTests]))
class RunTasksSpec {

  private val packageName = "foo.bar"
  private val mainClassName0 = "Main0"
  private val mainClassName1 = "Main1"
  private val mainClassName2 = "Main2"
  private val noDependencies = Map.empty[String, Set[String]]
  private val args = List("arg0", "arg1", "multiple words")

  private object ArtificialSources {
    val RunnableClass0 = s"""package $packageName
                            |object $mainClassName0 {
                            |  def main(args: Array[String]): Unit = {
                            |    println(s"$mainClassName0: $${args.mkString(", ")}")
                            |  }
                            |}""".stripMargin
    val RunnableClass1 = s"""package $packageName
                            |object $mainClassName1 {
                            |  def main(args: Array[String]): Unit = {
                            |    println(s"$mainClassName1: $${args.mkString(", ")}")
                            |  }
                            |}""".stripMargin
    val NotRunnable = s"""package $packageName
                         |object NotRunnable {
                         |  def foo(): Int = 42
                         |}""".stripMargin
    val InheritedRunnable = s"""package $packageName
                               |class BaseRunnable {
                               |  def main(args: Array[String]): Unit = {
                               |    println(s"$mainClassName2: $${args.mkString(", ")}")
                               |  }
                               |}
                               |object $mainClassName2 extends BaseRunnable""".stripMargin
  }

  @Test
  def doesntDetectNonRunnableClasses() = {
    val projectName = "test-project"
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.NotRunnable))
    val javaEnv = JavaEnv.default
    checkAfterCleanCompilation(projectsStructure,
                               noDependencies,
                               rootProjectName = projectName,
                               javaEnv = javaEnv,
                               quiet = true) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project)
      assertEquals(0, mainClasses.length.toLong)
    }
  }

  @Test
  def canDetectOneMainClass() = {
    val projectName = "test-project"
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.RunnableClass0))
    val javaEnv = JavaEnv.default
    checkAfterCleanCompilation(projectsStructure,
                               noDependencies,
                               rootProjectName = projectName,
                               javaEnv = javaEnv,
                               quiet = true) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project)
      assertEquals(1, mainClasses.length.toLong)
      assertEquals(s"$packageName.$mainClassName0", mainClasses(0))
    }
  }

  @Test
  def canDetectMultipleMainClasses() = {
    val projectName = "test-project"
    val projectsStructure = Map(
      projectName -> Map("A.scala" -> ArtificialSources.RunnableClass0,
                         "B.scala" -> ArtificialSources.RunnableClass1))
    val javaEnv = JavaEnv.default
    checkAfterCleanCompilation(projectsStructure,
                               noDependencies,
                               rootProjectName = projectName,
                               javaEnv = javaEnv,
                               quiet = true) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project).sorted
      assertEquals(2, mainClasses.length.toLong)
      assertEquals(s"$packageName.$mainClassName0", mainClasses(0))
      assertEquals(s"$packageName.$mainClassName1", mainClasses(1))
    }
  }

  @Test
  def runCanSeeCompileResources: Unit = {
    val projectName = "with-resources"
    val mainClassName = "Main"
    val state = loadTestProject(projectName)
    val command = Commands.Run(projectName, Some(mainClassName), args = List.empty)
    runAndCheck(state, command) { messages =>
      assert(messages.contains(("info", "OK")))
    }
  }

  @Test
  def canRunInheritedMain: Unit = {
    val projectName = "test-project"
    val sources = ArtificialSources.InheritedRunnable :: Nil
    val mainClass = s"$packageName.$mainClassName2"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName2: ${args.mkString(", ")}")))
    }
  }

  @Test
  def canRunSpecifiedMainSingleChoice: Unit = {
    val projectName = "test-project"
    val sources = ArtificialSources.RunnableClass0 :: Nil
    val mainClass = s"$packageName.$mainClassName0"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName0: ${args.mkString(", ")}")))
    }
  }

  @Test
  def canRunSpecifiedMainMultipleChoices: Unit = {
    val projectName = "test-project"
    val sources = ArtificialSources.RunnableClass0 :: ArtificialSources.RunnableClass1 :: Nil
    val mainClass = s"$packageName.$mainClassName1"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName1: ${args.mkString(", ")}")))
    }
  }

  @Test
  def canRunApplicationThatRequiresInput = {
    object Sources {
      val `A.scala` =
        """object Foo {
          |  def main(args: Array[String]): Unit = {
          |    println("Hello, World! I'm waiting for input")
          |    println(new java.util.Scanner(System.in).nextLine())
          |    println("I'm done!")
          |  }
          |}
        """.stripMargin
    }

    val logger = new RecordingLogger
    val structure = Map("A" -> Map("A.scala" -> Sources.`A.scala`))
    val scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance
    val javaEnv: JavaEnv = JavaEnv.default
    TestUtil.withState(structure, Map.empty, scalaInstance = scalaInstance, javaEnv = javaEnv) {
      (state0: State) =>
        // It has to contain a new line for the process to finish! ;)
        val ourInputStream = new ByteArrayInputStream("Hello!\n".getBytes(StandardCharsets.UTF_8))
        val hijackedCommonOptions = state0.commonOptions.copy(in = ourInputStream)
        val state = state0.copy(logger = logger).copy(commonOptions = hijackedCommonOptions)
        val projects = state.build.projects
        val projectA = getProject("A", state)
        val action = Run(Commands.Run("A"))
        val duration = Duration.apply(15, TimeUnit.SECONDS)
        def msgs = logger.getMessages
        val compiledState =
          try TestUtil.blockingExecute(action, state, duration)
          catch { case t: Throwable => println(msgs.mkString("\n")); throw t }
        assert(compiledState.status.isOk)
    }
  }

  @Test
  def canCancelNeverEndingApplication = {
    object Sources {
      val `A.scala` =
        """object Foo {
          |  def main(args: Array[String]): Unit = {
          |    println("Starting infinity.")
          |    while (true) ()
          |  }
          |}
        """.stripMargin
    }

    val logger = new RecordingLogger
    val structure = Map("A" -> Map("A.scala" -> Sources.`A.scala`))
    val scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance
    val javaEnv: JavaEnv = JavaEnv.default
    TestUtil.withState(structure, Map.empty, scalaInstance = scalaInstance, javaEnv = javaEnv) {
      (state0: State) =>
        // It has to contain a new line for the process to finish! ;)
        val ourInputStream = new ByteArrayInputStream("Hello!\n".getBytes(StandardCharsets.UTF_8))
        val hijackedCommonOptions = state0.commonOptions.copy(in = ourInputStream)
        val state = state0.copy(logger = logger).copy(commonOptions = hijackedCommonOptions)
        val projects = state.build.projects
        val projectA = getProject("A", state)
        val action = Run(Commands.Run("A"))
        val duration = Duration.apply(13, TimeUnit.SECONDS)
        val cancelTime = Duration.apply(7, TimeUnit.SECONDS)
        def msgs = logger.getMessages
        val runTask = TestUtil.interpreterTask(action, state)
        val handle = runTask.runAsync(ExecutionContext.ioScheduler)
        val driver = ExecutionContext.ioScheduler.scheduleOnce(cancelTime) { handle.cancel() }

        val runState = {
          try Await.result(handle, duration)
          catch {
            case NonFatal(t) =>
              driver.cancel()
              handle.cancel()
              println(msgs.mkString("\n"))
              throw t
          }
        }

        assert(msgs.filter(_._1 == "info").exists(_._2.contains("Starting infinity.")))
        assert(!runState.status.isOk)
    }
  }
}
