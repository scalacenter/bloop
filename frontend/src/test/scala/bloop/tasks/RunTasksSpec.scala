package bloop.tasks

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.experimental.categories.Category

import bloop.cli.Commands
import bloop.engine.{Interpreter, Run, State}
import bloop.engine.tasks.Tasks
import bloop.exec.JavaEnv
import bloop.logging.{ProcessLogger, RecordingLogger}
import bloop.tasks.TestUtil.{
  checkAfterCleanCompilation,
  getProject,
  loadTestProject,
  runAndCheck
}

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

}
