package bloop.tasks

import org.junit.Test
import org.junit.Assert.assertEquals

import bloop.cli.Commands
import bloop.engine.{Interpreter, Run}
import bloop.engine.tasks.Tasks
import bloop.logging.{BloopLogger, ProcessLogger, RecordingLogger}
import bloop.tasks.ProjectHelpers.{checkAfterCleanCompilation, getProject}

class RunTasksSpec {

  private val projectName = ProjectHelpers.RootProject
  private val packageName = "foo.bar"
  private val mainClassName0 = "Main0"
  private val mainClassName1 = "Main1"
  private val mainClassName2 = "Main2"
  private val noDependencies = Map.empty[String, Set[String]]
  private val args = List("arg0", "arg1", "multiple words")
  private val EOL = System.lineSeparator

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

  private def runAndCheck(sources: Seq[String], cmd: Commands.Command)(
      check: List[(String, String)] => Unit): Unit = {
    val namedSources = sources.zipWithIndex.map { case (src, idx) => s"src$idx.scala" -> src }.toMap
    val projectsStructure = Map(projectName -> namedSources)
    checkAfterCleanCompilation(projectsStructure, noDependencies, quiet = true) { state =>
      val recordingLogger = new RecordingLogger
      val recordingStream = ProcessLogger.toOutputStream(recordingLogger.info _)
      val logger = BloopLogger("recording")
      BloopLogger.update(logger, recordingStream)
      val project = getProject(projectName, state)
      val _ = Interpreter.execute(Run(cmd), state)
      check(recordingLogger.getMessages)
    }
  }

  @Test
  def doesntDetectNonRunnableClasses() = {
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.NotRunnable))
    checkAfterCleanCompilation(projectsStructure, noDependencies, quiet = true) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project)
      assertEquals(0, mainClasses.length.toLong)
    }
  }

  @Test
  def canDetectOneMainClass() = {
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.RunnableClass0))
    checkAfterCleanCompilation(projectsStructure, noDependencies, quiet = true) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project)
      assertEquals(1, mainClasses.length.toLong)
      assertEquals(s"$packageName.$mainClassName0", mainClasses(0))
    }
  }

  @Test
  def canDetectMultipleMainClasses() = {
    val projectsStructure = Map(
      projectName -> Map("A.scala" -> ArtificialSources.RunnableClass0,
                         "B.scala" -> ArtificialSources.RunnableClass1))
    checkAfterCleanCompilation(projectsStructure, noDependencies, quiet = true) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project).sorted
      assertEquals(2, mainClasses.length.toLong)
      assertEquals(s"$packageName.$mainClassName0", mainClasses(0))
      assertEquals(s"$packageName.$mainClassName1", mainClasses(1))
    }
  }

  @Test
  def canRunSpecifiedMainSingleChoice() = {
    val sources = ArtificialSources.RunnableClass0 :: Nil
    val mainClass = s"$packageName.$mainClassName0"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName0: ${args.mkString(", ")}$EOL")))
    }
  }

  @Test
  def canRunSpecifiedMainMultipleChoices() = {
    val sources = ArtificialSources.RunnableClass0 :: ArtificialSources.RunnableClass1 :: Nil
    val mainClass = s"$packageName.$mainClassName1"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName1: ${args.mkString(", ")}$EOL")))
    }
  }

  @Test
  def canRunInheritedMain() = {
    val sources = ArtificialSources.InheritedRunnable :: Nil
    val mainClass = s"$packageName.$mainClassName2"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName2: ${args.mkString(", ")}$EOL")))
    }
  }

}
