package bloop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import bloop.cli.Commands
import bloop.cli.ExitStatus
import bloop.data.JdkConfig
import bloop.engine.Dag
import bloop.engine.ExecutionContext
import bloop.engine.Run
import bloop.engine.State
import bloop.engine.tasks.Tasks
import bloop.logging.RecordingLogger
import bloop.testing.BloopHelpers
import bloop.util.TestProject
import bloop.util.TestUtil
import bloop.util.TestUtil.checkAfterCleanCompilation
import bloop.util.TestUtil.getProject
import bloop.util.TestUtil.loadTestProject
import bloop.util.TestUtil.runAndCheck

import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class RunSpec extends BloopHelpers {
  private val packageName = "foo.bar"
  private val mainClassName0 = "Main0"
  private val mainClassName1 = "Main1"
  private val mainClassName2 = "Main2"
  private val noDependencies = Map.empty[String, Set[String]]
  private val args = List("arg0", "arg1", "multiple words")

  private object ArtificialSources {
    val RunnableClass0: String = s"""package $packageName
                                    |object $mainClassName0 {
                                    |  def main(args: Array[String]): Unit = {
                                    |    println(s"$mainClassName0: $${args.mkString(", ")}")
                                    |  }
                                    |}""".stripMargin
    val RunnableClass1: String = s"""package $packageName
                                    |object $mainClassName1 {
                                    |  def main(args: Array[String]): Unit = {
                                    |    println(s"$mainClassName1: $${args.mkString(", ")}")
                                    |  }
                                    |}""".stripMargin
    val NotRunnable: String = s"""package $packageName
                                 |object NotRunnable {
                                 |  def foo(): Int = 42
                                 |}""".stripMargin
    val InheritedRunnable: String = s"""package $packageName
                                       |class BaseRunnable {
                                       |  def main(args: Array[String]): Unit = {
                                       |    println(s"$mainClassName2: $${args.mkString(", ")}")
                                       |  }
                                       |}
                                       |object $mainClassName2 extends BaseRunnable""".stripMargin
  }

  @Test
  def doesntDetectNonRunnableClasses(): Unit = {
    val projectName = "test-project"
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.NotRunnable))
    val jdkConfig = JdkConfig.default
    checkAfterCleanCompilation(
      projectsStructure,
      noDependencies,
      rootProjects = List(projectName),
      jdkConfig = jdkConfig,
      quiet = true
    ) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project)
      assertEquals(0, mainClasses.length.toLong)
    }
  }

  @Test
  def canDetectOneMainClass(): Unit = {
    val projectName = "test-project"
    val projectsStructure = Map(projectName -> Map("A.scala" -> ArtificialSources.RunnableClass0))
    val jdkConfig = JdkConfig.default
    checkAfterCleanCompilation(
      projectsStructure,
      noDependencies,
      rootProjects = List(projectName),
      jdkConfig = jdkConfig,
      quiet = true
    ) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project)
      assertEquals(1, mainClasses.length.toLong)
      assertEquals(s"$packageName.$mainClassName0", mainClasses(0))
    }
  }

  @Test
  def canDetectMultipleMainClasses(): Unit = {
    val projectName = "test-project"
    val projectsStructure = Map(
      projectName -> Map(
        "A.scala" -> ArtificialSources.RunnableClass0,
        "B.scala" -> ArtificialSources.RunnableClass1
      )
    )
    val jdkConfig = JdkConfig.default
    checkAfterCleanCompilation(
      projectsStructure,
      noDependencies,
      rootProjects = List(projectName),
      jdkConfig = jdkConfig,
      quiet = true
    ) { state =>
      val project = getProject(projectName, state)
      val mainClasses = Tasks.findMainClasses(state, project).sorted
      assertEquals(2, mainClasses.length.toLong)
      assertEquals(s"$packageName.$mainClassName0", mainClasses(0))
      assertEquals(s"$packageName.$mainClassName1", mainClasses(1))
    }
  }

  @Test
  def runCanSeeCompileResources: Unit = {
    TestUtil.runOnlyOnJava8 {
      val mainClassName = "hello.AppWithResources"
      val state = loadTestProject("cross-test-build-scalajs-0.6")
      val command = Commands.Run(List("test-project"), Some(mainClassName), args = List.empty)
      runAndCheck(state, command) { messages =>
        assert(messages.contains(("info", "Resources were found")))
      }
    }
  }

  @Test
  def runCanSeeConflictingResources: Unit = {
    TestUtil.runOnlyOnJava8 {
      val mainClassName = "hello.AppWithConflictingResources"
      val state = loadTestProject("cross-test-build-scalajs-0.6")
      val command = Commands.Run(List("test-project-test"), Some(mainClassName), args = List.empty)
      runAndCheck(state, command) { messages =>
        assert(messages.contains(("info", "Resource application.conf was successfully loaded")))
      }
    }
  }

  @Test
  def runIncludesTransitiveResourcesInAggregatedProjects: Unit = {
    TestUtil.runOnlyOnJava8 {
      val target = "cross-test-build-scalajs-0-6"
      val state = loadTestProject("cross-test-build-scalajs-0.6")
      state.build.getProjectFor(target) match {
        case Some(rootWithAggregation) =>
          val dag = state.build.getDagFor(rootWithAggregation)
          // Create dependent resources so that they appear in the full dependency classpath
          val dependentResources = Dag.dfs(dag).flatMap(_.resources)
          dependentResources.foreach { resource =>
            if (resource.exists) ()
            else Files.createDirectories(resource.underlying)
          }

          val fullClasspath = rootWithAggregation.fullRuntimeClasspath(dag, state.client).toList
          Assert.assertFalse(dependentResources.isEmpty)
          dependentResources.foreach { r =>
            Assert.assertTrue(s"Missing $r in $fullClasspath", fullClasspath.contains(r))
          }
        case None => Assert.fail(s"Missing root $target")
      }
    }
  }

  @Test
  def canRunMainFromSourceDependency: Unit = {
    TestUtil.runOnlyOnJava8 {
      val mainClassName = "hello.App"
      val state = loadTestProject("cross-test-build-scalajs-0.6")
      val command = Commands.Run(List("test-project-test"), Some(mainClassName), args = List.empty)
      runAndCheck(state, command) { messages =>
        assert(messages.contains(("info", "Hello, world!")))
      }
    }
  }

  @Test
  def canRunDefaultMainClass: Unit = {
    TestUtil.runOnlyOnJava8 {
      // The default main class is set to hello.App build.sbt. Therefore, no error must be triggered here.
      val state = loadTestProject("cross-test-build-scalajs-0.6")
      val command = Commands.Run(List("test-project"), None, args = List.empty)
      runAndCheck(state, command) { messages =>
        assert(messages.contains(("info", "Hello, world!")))
      }
    }
  }

  @Test
  def canRunMainFromBinaryDependency: Unit = {
    TestUtil.runOnlyOnJava8 {
      val mainClassName = "App"
      val state = loadTestProject("cross-test-build-scalajs-0.6")
      val command = Commands.Run(List("test-project"), Some(mainClassName), args = List.empty)
      runAndCheck(state, command) { messages =>
        assert(messages.contains(("info", "Hello, world!")))
      }
    }
  }

  @Test
  def setCorrectCwd: Unit = {
    TestUtil.runOnlyOnJava8 {
      val mainClassName = "hello.ShowCwd"
      val state = loadTestProject("cross-test-build-scalajs-0.6")
      val command = Commands.Run(List("test-project"), Some(mainClassName), args = List.empty)
      val targetMsg = "cross-test-build-scalajs-0.6"

      runAndCheck(state, command) { messages =>
        assert(
          messages.reverse.find(_._1 == "info").exists(_._2.endsWith(targetMsg))
        )
      }
    }
  }

  @Test
  def canRunInheritedMain: Unit = {
    val projectName = "test-project"
    val sources = ArtificialSources.InheritedRunnable :: Nil
    val mainClass = s"$packageName.$mainClassName2"
    val command = Commands.Run(List(projectName), Some(mainClass), args = args)
    runAndCheck(projectName, sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName2: ${args.mkString(", ")}")))
    }
  }

  @Test
  def canRunSpecifiedMainSingleChoice: Unit = {
    val projectName = "test-project"
    val sources = ArtificialSources.RunnableClass0 :: Nil
    val mainClass = s"$packageName.$mainClassName0"
    val command = Commands.Run(List(projectName), Some(mainClass), args = args)
    runAndCheck(projectName, sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName0: ${args.mkString(", ")}")))
    }
  }

  @Test
  def canRunSpecifiedMainMultipleChoices: Unit = {
    val projectName = "test-project"
    val sources = ArtificialSources.RunnableClass0 :: ArtificialSources.RunnableClass1 :: Nil
    val mainClass = s"$packageName.$mainClassName1"
    val command = Commands.Run(List(projectName), Some(mainClass), args = args)
    runAndCheck(projectName, sources, command) { messages =>
      assert(messages.contains(("info", s"$mainClassName1: ${args.mkString(", ")}")))
    }
  }

  @Test
  def canRunApplicationThatRequiresInput: Unit = {
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
    TestUtil.testState(structure, Map.empty) { (state0: State) =>
      // It has to contain a new line for the process to finish! ;)
      val ourInputStream = new ByteArrayInputStream("Hello!\n".getBytes(StandardCharsets.UTF_8))
      val hijackedCommonOptions = state0.commonOptions.copy(in = ourInputStream)
      val state = state0.copy(logger = logger).copy(commonOptions = hijackedCommonOptions)
      val action = Run(Commands.Run(List("A")))
      val duration = Duration.apply(15, TimeUnit.SECONDS)
      def msgs = logger.getMessages
      val compiledState =
        try TestUtil.blockingExecute(action, state, duration)
        catch { case t: Throwable => println(msgs.mkString("\n")); throw t }
      assert(compiledState.status.isOk)
    }
  }

  @Test
  def canCancelNeverEndingApplication: Unit = {
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
    TestUtil.testState(structure, Map.empty) { (state0: State) =>
      // It has to contain a new line for the process to finish! ;)
      val ourInputStream = new ByteArrayInputStream("Hello!\n".getBytes(StandardCharsets.UTF_8))
      val hijackedCommonOptions = state0.commonOptions.copy(in = ourInputStream)
      val state = state0.copy(logger = logger).copy(commonOptions = hijackedCommonOptions)
      val action = Run(Commands.Run(List("A")))
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

  @Test
  def runApplicationAfterIncrementalChanges(): Unit = {
    val RootProject = "target-project"
    object Sources {
      val `A.scala` = "object Dep { val s = 1 }"
      val `B.scala` = "object TestRoot extends App { println(Dep.s) }"
      val `A2.scala` = "object Dep { val t = 1 }"
    }

    val structure = Map(
      "A" -> Map("A.scala" -> Sources.`A.scala`),
      RootProject -> Map("B.scala" -> Sources.`B.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map(RootProject -> Set("A"))
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      assertEquals(logger.compilingInfos.size.toLong, 2.toLong)
      TestUtil.ensureCompilationInAllTheBuild(state)

      // Modify the contents of a source in `A` to trigger recompilation in root
      val projectA = state.build.getProjectFor("A").get
      val sourceA = projectA.sources.head.resolve("A.scala")
      assert(Files.exists(sourceA.underlying), s"Source $sourceA does not exist")
      Files.write(sourceA.underlying, Sources.`A2.scala`.getBytes(StandardCharsets.UTF_8))

      val action = Run(Commands.Run(List(RootProject), incremental = true))
      TestUtil.blockingExecute(action, state)

      assertEquals(4.toLong, logger.compilingInfos.size.toLong)
      val errors = logger.getMessages.filter(_._1 == "error")
      val compilationError = errors.filter(_._2.contains("value s is not a member of object Dep"))
      assertTrue("A compilation error is missing", compilationError.size == 1)
      val msgs = errors.filter(_._2.contains("Could not find or load main class TestRoot"))
      assertTrue("An application was run when compilation failed.", msgs.size == 0)
      TestUtil.ensureCompilationInAllTheBuild(state)
    }
  }

  @Test
  def runUsesRuntimeClasspath(): Unit = {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `a/foo/A.scala` =
          """/a/foo/A.scala
            |package foo
            |class A""".stripMargin
        val `b/foo/bar/A.scala` =
          """/b/foo/bar/A.scala
            |package foo.bar
            |class A""".stripMargin
        val `c/pkg/Main.scala` =
          """/c/pkg/Main.scala
            |package pkg
            |import foo._
            |import bar._
            |object Main {
            |  def main(args: Array[String]): Unit = {
            |    val a = new A // Would be ambiguous if project a was on the compilation classpath
            |    val fooA = getClass.getClassLoader.loadClass("foo.A")
            |    println("Compile-time: " + a.getClass)
            |    println("Reflection: " + fooA)
            |  }
            |}""".stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`a/foo/A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`b/foo/bar/A.scala`), List(`A`))
      val `C` = TestProject(
        workspace,
        "c",
        List(Sources.`c/pkg/Main.scala`),
        List(`B`),
        strictDependencies = false
      )
      val `D` = TestProject(
        workspace,
        "d",
        List(Sources.`c/pkg/Main.scala`),
        List(`B`),
        strictDependencies = true
      )
      val projects = List(`A`, `B`, `C`, `D`)
      val state = loadState(workspace, projects, logger)

      val failedCompile = state.compile(`C`) // Will fail because `new A` is ambiguous
      assertEquals(ExitStatus.CompilationError, failedCompile.status)

      val compiledState = failedCompile.compile(`D`)
      assertEquals(ExitStatus.Ok, compiledState.status)

      val runState = compiledState.run(`D`)
      assertEquals(ExitStatus.Ok, runState.status)
      assertTrue(logger.infos.contains("Compile-time: class foo.bar.A"))
      assertTrue(logger.infos.contains("Reflection: class foo.A"))
    }
  }

  @Test
  def runSeesRuntimeResources(): Unit = {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `a/A.scala` =
          """/a/A.scala
            |object A {
            |  def main(args: Array[String]): Unit = {
            |    val res = getClass.getClassLoader.getResourceAsStream("resource.txt")
            |    val content = scala.io.Source.fromInputStream(res).mkString
            |    assert("goodbye" == content)
            |  }
            |}""".stripMargin
      }
      object Resources {
        val `a/compile-resources/resource.txt` =
          """/resource.txt
            |hello""".stripMargin
        val `a/run-resources/resource.txt` =
          """/resource.txt
            |goodbye""".stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`a/A.scala`),
        resources = List(Resources.`a/compile-resources/resource.txt`),
        runtimeResources = Some(List(Resources.`a/run-resources/resource.txt`))
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val runState = state.run(`A`)
      assertEquals(ExitStatus.Ok, runState.status)
    }
  }

  @Test
  def runUsesRuntimeEnvironment(): Unit = {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `a/A.scala` =
          """/a/A.scala
            |object A {
            |  def main(args: Array[String]): Unit = {
            |    assert("goodbye" == sys.props("test"))
            |  }
            |}""".stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`a/A.scala`),
        runtimeJvmConfig =
          Some(JdkConfig.toConfig(JdkConfig.default.copy(javaOptions = Array("-Dtest=goodbye"))))
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val runState = state.run(`A`)
      assertEquals(ExitStatus.Ok, runState.status)
    }
  }

}
