package bloop.tasks

import org.junit.Test
import org.junit.Assert.assertEquals

import bloop.cli.Commands
import bloop.engine.{Interpreter, Run}
import bloop.engine.tasks.Tasks
import bloop.exec.JavaEnv
import bloop.logging.{ProcessLogger, RecordingLogger}
import bloop.tasks.ProjectHelpers.{checkAfterCleanCompilation, getProject}

class RunTasksSpec {

  private val projectName = ProjectHelpers.RootProject
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

  private def runAndCheck(sources: Seq[String], cmd: Commands.Command)(
      check: List[(String, String)] => Unit): Unit = {
    val namedSources = sources.zipWithIndex.map { case (src, idx) => s"src$idx.scala" -> src }.toMap
    val projectsStructure = Map(projectName -> namedSources)
    checkAfterCleanCompilation(projectsStructure, noDependencies, quiet = true) { state =>
      val recordingLogger = new RecordingLogger
      val recordingStream = ProcessLogger.toOutputStream(recordingLogger.info _)
      val recordingState = state.copy(logger = recordingLogger)
      val project = getProject(projectName, recordingState)
      val _ = Interpreter.execute(Run(cmd), recordingState)
      check(recordingLogger.getMessages)
    }
  }

  @Test
  def doesntDetectNonRunnableClasses() = {
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.NotRunnable))
    val javaEnv = JavaEnv.default(fork = false)
    checkAfterCleanCompilation(projectsStructure, noDependencies, javaEnv = javaEnv, quiet = true) {
      state =>
        val project = getProject(projectName, state)
        val mainClasses = Tasks.findMainClasses(state, project)
        assertEquals(0, mainClasses.length.toLong)
    }
  }

  @Test
  def canDetectOneMainClass() = {
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.RunnableClass0))
    val javaEnv = JavaEnv.default(fork = false)
    checkAfterCleanCompilation(projectsStructure, noDependencies, javaEnv = javaEnv, quiet = true) {
      state =>
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
    val javaEnv = JavaEnv.default(fork = false)
    checkAfterCleanCompilation(projectsStructure, noDependencies, javaEnv = javaEnv, quiet = true) {
      state =>
        val project = getProject(projectName, state)
        val mainClasses = Tasks.findMainClasses(state, project).sorted
        assertEquals(2, mainClasses.length.toLong)
        assertEquals(s"$packageName.$mainClassName0", mainClasses(0))
        assertEquals(s"$packageName.$mainClassName1", mainClasses(1))
    }
  }

  @Test
  def canRunSpecifiedMainSingleChoiceWithoutForking() =
    canRunSpecifiedMainSingleChoice(fork = false)

  @Test
  def canRunSpecifiedMainSingleChoiceWithForking() = canRunSpecifiedMainSingleChoice(fork = true)

  @Test
  def canRunSpecifiedMainMultipleChoicesWithoutForking() =
    canRunSpecifiedMainMultipleChoices(fork = false)

  @Test
  def canRunSpecifiedMainMultipleChoicesWithForking() =
    canRunSpecifiedMainMultipleChoices(fork = true)

  @Test
  def canRunInheritedMainWithoutForking() = canRunInheritedMain(fork = false)

  @Test
  def canRunInheritedMainWithForking() = canRunInheritedMain(fork = true)

  private def runAndCheck(sources: Seq[String], fork: Boolean, cmd: Commands.Command)(
      check: List[(String, String)] => Unit): Unit = {
    val namedSources = sources.zipWithIndex.map { case (src, idx) => s"src$idx.scala" -> src }.toMap
    val projectsStructure = Map(projectName -> namedSources)
    val javaEnv = JavaEnv.default(fork)
    checkAfterCleanCompilation(projectsStructure, noDependencies, javaEnv = javaEnv, quiet = true) {
      state =>
        val recordingLogger = new RecordingLogger
        val recordingStream = ProcessLogger.toOutputStream(recordingLogger.info _)
        val recordingState = state.copy(logger = recordingLogger)
        val project = getProject(projectName, recordingState)
        val _ = Interpreter.execute(Run(cmd), recordingState)
        check(recordingLogger.getMessages)
    }
  }

  private def canRunInheritedMain(fork: Boolean) = {
    val sources = ArtificialSources.InheritedRunnable :: Nil
    val mainClass = s"$packageName.$mainClassName2"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, fork, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName2: ${args.mkString(", ")}")))
    }
  }

  private def canRunSpecifiedMainSingleChoice(fork: Boolean): Unit = {
    val sources = ArtificialSources.RunnableClass0 :: Nil
    val mainClass = s"$packageName.$mainClassName0"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, fork, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName0: ${args.mkString(", ")}")))
    }
  }

  private def canRunSpecifiedMainMultipleChoices(fork: Boolean) = {
    val sources = ArtificialSources.RunnableClass0 :: ArtificialSources.RunnableClass1 :: Nil
    val mainClass = s"$packageName.$mainClassName1"
    val command = Commands.Run(projectName, Some(mainClass), args = args)
    runAndCheck(sources, fork, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName1: ${args.mkString(", ")}")))
    }
  }

}
