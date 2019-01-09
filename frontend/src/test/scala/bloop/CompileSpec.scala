package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import bloop.cli.{Commands, ExitStatus}
import bloop.config.Config
import bloop.logging.{Logger, RecordingLogger}
import bloop.util.{TestProject, TestUtil}
import bloop.util.TestUtil.{
  RootProject,
  checkAfterCleanCompilation,
  ensureCompilationInAllTheBuild,
  getProject,
  hasPreviousResult,
  noPreviousAnalysis
}
import bloop.engine.tasks.Tasks
import bloop.engine.{Feedback, Run, State}
import bloop.io.AbsolutePath
import monix.execution.CancelableFuture
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.experimental.categories.Category
import org.junit.{Assert, Test}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

@Category(Array(classOf[bloop.FastTests]))
class CompileSpec {
  object ArtificialSources {
    val `A.scala` = "package p0\nclass A"
    val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
    val `B2.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
    val `C2.scala` = "package p2\nimport p0.A\nobject C extends A"
    val `Dotty.scala` = "package p0\nobject Foo { val x: String | Int = 1 }"
  }

  @Test
  def compileAnEmptyProject = {
    val logger = new RecordingLogger
    val projectStructures = Map(RootProject -> Map.empty[String, String])
    val dependencies = Map.empty[String, Set[String]]
    checkAfterCleanCompilation(
      projectStructures,
      dependencies,
      quiet = true,
      useSiteLogger = Some(logger)
    ) { state =>
      val targetProject = getProject(RootProject, state)
      assertTrue(state.status.isOk)
      // Result is empty even if compilation didn't contain any Scala source files
      assertTrue(hasPreviousResult(targetProject, state))

      // Make sure that no Scala source files are ever compiled if they don't show up in project sources
      val newSourcePath = state.build.origin.resolve("Z.scala")
      Files.write(newSourcePath.underlying, "object A".getBytes(StandardCharsets.UTF_8))

      // Let's compile the root project again, we should have no more logs
      val newState = TestUtil.blockingExecute(Run(Commands.Compile(List(RootProject))), state)
      assertTrue(newState.status.isOk)
      assertTrue(hasPreviousResult(targetProject, newState))
      assertFalse(logger.getMessages.exists(_._2.contains("Compiling")))
    }
  }

  def scalaInstance2124(logger: Logger): ScalaInstance = ScalaInstance.resolve(
    "org.scala-lang",
    "scala-compiler",
    "2.12.4",
    logger
  )

  @Test
  def compileScalaAndJavaWithMissingDependency(): Unit = {
    val logger = new RecordingLogger
    // Add missing dependency to check we don't fail during build load, we just emit a warning
    val missingDependency = "i-dont-exist"
    val dependencies = Map[String, Set[String]](RootProject -> Set(missingDependency))
    val structures = Map(
      RootProject -> Map("A.scala" -> "object A", "B.java" -> "public class B {}")
    )

    checkAfterCleanCompilation(
      structures,
      dependencies,
      scalaInstance = scalaInstance2124(logger),
      useSiteLogger = Some(logger),
      order = Config.JavaThenScala
    ) { state =>
      val targetProject = getProject(RootProject, state)
      val missingDepMsg = Feedback.detectMissingDependencies(targetProject, List(missingDependency))
      assertTrue(logger.getMessagesAt(Some("warn")).exists(_.startsWith(missingDepMsg.get)))
    }

    val errors = TestUtil.errorsFromLogger(logger)
    assert(errors.size == 0)
  }

  @Test
  def compileAndDetectJavaErrors(): Unit = {
    val logger = new RecordingLogger
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(
      RootProject -> Map("A.scala" -> "class A", "B.java" -> "public class B extends A {}")
    )

    checkAfterCleanCompilation(
      structures,
      dependencies,
      scalaInstance = scalaInstance2124(logger),
      useSiteLogger = Some(logger),
      order = Config.JavaThenScala
    )(_ => ())

    try {
      val errors = TestUtil.errorsFromLogger(logger)
      assert(errors.size == 3)
      assert(errors.exists(_.contains("cannot find symbol")))
    } finally {
      //logger.dump()
    }
  }

  @Test
  def compileAndDetectScalaErrors(): Unit = {
    val logger = new RecordingLogger
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "abject A"))

    checkAfterCleanCompilation(
      structures,
      dependencies,
      scalaInstance = scalaInstance2124(logger),
      useSiteLogger = Some(logger)
    )(_ => ())

    val errors = TestUtil.errorsFromLogger(logger)

    assert(
      errors.exists(_.contains("expected class or object definition")),
      "Missing compiler errors"
    )
  }

  @Test
  def compileAndDetectInvalidScalacFlags(): Unit = {
    val logger = new RecordingLogger
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))

    checkAfterCleanCompilation(
      structures,
      dependencies,
      scalaInstance = scalaInstance2124(logger),
      useSiteLogger = Some(logger)
    ) { state =>
      assertTrue(state.status.isOk)

      // The project successfully compiled with no flags, let's add a wrong flag and see it fail
      val newProjects =
        state.build.projects.map(p => p.copy(scalacOptions = "-Ytyper-degug" :: p.scalacOptions))
      val newState = state.copy(build = state.build.copy(projects = newProjects))
      val erroneousState =
        TestUtil.blockingExecute(Run(Commands.Compile(List(RootProject))), newState)
      assertFalse(erroneousState.status.isOk)

      val errors = TestUtil.errorsFromLogger(logger)
      assert(
        errors.exists(_.contains("bad option: '-Ytyper-degug'")),
        "Missing compiler errors about scalac flgg"
      )
    }
  }

  @Test
  def compileWithScala2124(): Unit = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.4", logger)
    simpleProject(scalaInstance)
  }

  @Test
  def compileWithScala2123(): Unit = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.3", logger)
    simpleProject(scalaInstance)
  }

  @Test
  def compileWithScala21111(): Unit = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.11.11", logger)
    simpleProject(scalaInstance)
  }

  @Test
  def compileTwoProjectsWithADependency(): Unit = {
    val projectsStructure = Map(
      "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      RootProject -> Map("B.scala" -> ArtificialSources.`B.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent"))
    checkAfterCleanCompilation(projectsStructure, dependencies, quiet = true) { (state: State) =>
      ensureCompilationInAllTheBuild(state)
    }
  }

  @Test
  def compileOneProjectWithTwoDependencies(): Unit = {
    val projectsStructure = Map(
      "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
      RootProject -> Map("C.scala" -> ArtificialSources.`C.scala`)
    )

    val logger = new RecordingLogger
    val dependencies = Map(RootProject -> Set("parent0", "parent1"))
    checkAfterCleanCompilation(
      projectsStructure,
      dependencies,
      useSiteLogger = Some(logger),
      quiet = true
    ) { (state: State) =>
      assertTrue(state.status.isOk)
      ensureCompilationInAllTheBuild(state)
      assertEquals(3.toLong, logger.compilingInfos.size.toLong)

      val rootProject = state.build.getProjectFor(RootProject).get
      val sourceC = rootProject.sources.head.resolve("C.scala").underlying
      assert(Files.exists(sourceC))
      Files.write(sourceC, "package p2; class C".getBytes)

      val action = Run(Commands.Compile(List(RootProject), incremental = true))
      val state2 = TestUtil.blockingExecute(action, state)

      assertTrue(state2.status.isOk)
      ensureCompilationInAllTheBuild(state2)
      assertEquals(4.toLong, logger.compilingInfos.size.toLong)
    }
  }

  @Test
  def unnecessaryProjectsAreNotCompiled(): Unit = {
    val logger = new RecordingLogger
    val projectsStructures = Map(
      "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
      RootProject -> Map("C.scala" -> ArtificialSources.`C2.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent"))
    checkAfterCleanCompilation(
      projectsStructures,
      dependencies,
      quiet = false,
      useSiteLogger = Some(logger)
    ) { (state: State) =>
      // The unrelated project should not have been compiled
      assertTrue(
        s"Project `unrelated` was compiled",
        noPreviousAnalysis(getProject("unrelated", state), state)
      )
      assertTrue(
        s"Project `parent` was not compiled",
        hasPreviousResult(getProject("parent", state), state)
      )
      assertTrue(
        s"Project `RootProject` was not compiled",
        hasPreviousResult(getProject(RootProject, state), state)
      )
    }
  }

  @Test
  def noResultWhenCompilationFails(): Unit = {
    val projectsStructure = Map(RootProject -> Map("Error.scala" -> "iwontcompile"))
    checkAfterCleanCompilation(projectsStructure, Map.empty, failure = true) { (state: State) =>
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} has a compilation result", noPreviousAnalysis(p, state))
      }
    }
  }

  @Test
  def printRangePositionsInDiagnostics(): Unit = {
    TestUtil.withinWorkspace { baseDir =>
      val project = TestProject(
        baseDir,
        "ticket-787",
        List(
          """/main/scala/A.scala
            |object A {
            |  "".lengthCompare("1".substring(0))
            |
            |  // Make range pos multi-line to ensure range pos doesn't work here
            |  "".lengthCompare("1".
            |    substring(0))
            |}""".stripMargin
        ),
        scalacOptions = List("-Yrangepos")
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A.scala` = project.srcFor("main/scala/A.scala")
      val state = TestUtil.loadStateFromProjects(baseDir, List(project)).copy(logger = logger)
      val action = Run(Commands.Compile(List("ticket-787")))
      val compiledState = TestUtil.blockingExecute(action, state)
      TestUtil.assertNoDiff(
        s"""
          |[E2] ${TestUtil.universalPath("ticket-787/src/main/scala/A.scala")}:6:14
          |     type mismatch;
          |      found   : String
          |      required: Int
          |     L6:     substring(0))
          |                      ^
          |[E1] ${TestUtil.universalPath("ticket-787/src/main/scala/A.scala")}:2:33
          |     type mismatch;
          |      found   : String
          |      required: Int
          |     L2:   "".lengthCompare("1".substring(0))
          |                            ^^^^^^^^^^^^^^^^
          |${TestUtil.universalPath("ticket-787/src/main/scala/A.scala")}: L2 [E1], L6 [E2]
          |'ticket-787' failed to compile.""".stripMargin,
        logger.errors.mkString(System.lineSeparator())
      )

      ()
    }
  }

  @Test
  def compileJavaProjectDependingOnScala(): Unit = {
    object Sources {
      val `A.scala` = "package foo; object Greeting { def greeting: String = \"Hello, World!\" }"
      val `B.java` =
        """
          |import foo.Greeting;
          |
          |public class B {
          |
          |    public static void main(String[] args) {
          |        // Prints "Hello, World" to the terminal window.
          |        System.out.println("Hello, World");
          |    }
          |
          |}
          """.stripMargin

      val `C.scala` =
        """
          |object DependencyOnScalaCode {
          |  println(foo.Greeting.greeting)
          |}
          """.stripMargin
    }

    val projectsStructures = Map(
      "parent" -> Map("A.scala" -> Sources.`A.scala`),
      "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
      RootProject -> Map("B.java" -> Sources.`B.java`, "C.scala" -> Sources.`C.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent"))
    checkAfterCleanCompilation(projectsStructures, dependencies, quiet = true) { (state: State) =>
      // The unrelated project should not have been compiled
      assertTrue(
        s"Project `unrelated` was compiled",
        noPreviousAnalysis(getProject("unrelated", state), state)
      )
      assertTrue(
        s"Project `parent` was not compiled",
        hasPreviousResult(getProject("parent", state), state)
      )
      assertTrue(
        s"Project `RootProject` was not compiled",
        hasPreviousResult(getProject(RootProject, state), state)
      )
    }
  }

  @Test
  def compileDiamondLikeStructure(): Unit = {
    object Sources {
      val `A.scala` = "package p0\nclass A"
      val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
      val `C.scala` = "package p2\nimport p0.A\nclass C extends A"
      val `D.scala` = "package p3\ntrait D"
      val `E.scala` = "package p3\nimport p1.B\nimport p2.C\nimport p3.D\nobject E extends B with D"
    }

    val structure = Map(
      "A" -> Map("A.scala" -> Sources.`A.scala`),
      "B" -> Map("B.scala" -> Sources.`B.scala`),
      "C" -> Map("C.scala" -> Sources.`C.scala`),
      "D" -> Map("D.scala" -> Sources.`D.scala`),
      RootProject -> Map("E.scala" -> Sources.`E.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map(
      RootProject -> Set("A", "B", "C", "D"),
      "B" -> Set("A"),
      "C" -> Set("A"),
      "D" -> Set("B", "C")
    )
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      assertEquals(5.toLong, logger.compilingInfos.size.toLong)
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
      }
    }
  }

  @Test
  def compileRepeatedSubTreeInProjects(): Unit = {
    object Sources {
      val `A.scala` = "package p0\nclass A"
      val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
      val `C.scala` = "package p2\nimport p1.B\nclass C extends B"
      val `D.scala` = "package p3\nimport p1.B\nclass D extends B"
      val `E.scala` = "package p4\nimport p2.C\nimport p3.D\nobject E extends C { println(new D) }"
    }

    val structure = Map(
      "A" -> Map("A.scala" -> Sources.`A.scala`),
      "B" -> Map("B.scala" -> Sources.`B.scala`),
      "C" -> Map("C.scala" -> Sources.`C.scala`),
      "D" -> Map("D.scala" -> Sources.`D.scala`),
      RootProject -> Map("E.scala" -> Sources.`E.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map(
      RootProject -> Set("A", "B", "C", "D"),
      "B" -> Set("A"),
      "C" -> Set("B", "A"),
      "D" -> Set("B", "A")
    )
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      assertEquals(5.toLong, logger.compilingInfos.size.toLong)
      ensureCompilationInAllTheBuild(state)
    }
  }

  @Test
  def compileBuildIncrementally(): Unit = {
    object Sources {
      val `A.scala` = "object Dep {\n  def fun(i: String) = s\"i\"\n}"
      val `B.scala` = "object TestRoot {\n  println(Dep.fun(\"1\"))\n}"
      val `A2.scala` = "object Dep {\n  def fun(i: Int) = s\"i\"\n}"
    }

    val structure = Map(
      "A" -> Map("A.scala" -> Sources.`A.scala`),
      RootProject -> Map("B.scala" -> Sources.`B.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map(RootProject -> Set("A"))
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      assertEquals(logger.compilingInfos.size.toLong, 2.toLong)
      ensureCompilationInAllTheBuild(state)

      // Modify the contents of a source in `A` to trigger recompilation in root
      val projectA = state.build.getProjectFor("A").get
      val sourceA = projectA.sources.head.resolve("A.scala")
      assert(Files.exists(sourceA.underlying), s"Source $sourceA does not exist")
      Files.write(sourceA.underlying, Sources.`A2.scala`.getBytes(StandardCharsets.UTF_8))

      val action = Run(Commands.Compile(List(RootProject), incremental = true))
      val state2 = TestUtil.blockingExecute(action, state)

      assertEquals(4.toLong, logger.compilingInfos.size.toLong)
      ensureCompilationInAllTheBuild(state)
    }
  }

  @Test
  def compileWithErrorAndRollbackToNoOp(): Unit = {
    // This test checks that we're using a transactional class file manager
    object Sources {
      val `A.scala` = "object A {\n  def foo(i: String): String = s\"i\"\n}"
      val `A2WithError.scala` = "object A {\n  def foo(i: Int): String = i\n}"
    }

    val structure = Map(
      RootProject -> Map("A.scala" -> Sources.`A.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map(RootProject -> Set[String]())
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      assertEquals(logger.compilingInfos.size.toLong, 1.toLong)
      assertEquals(logger.errors.size.toLong, 0.toLong)
      ensureCompilationInAllTheBuild(state)

      // Force a compilation cycle and get an error
      val rootProject = state.build.getProjectFor(RootProject).get
      val sourceA = rootProject.sources.head.resolve("A.scala")
      assert(Files.exists(sourceA.underlying), s"Source $sourceA does not exist")
      Files.write(sourceA.underlying, Sources.`A2WithError.scala`.getBytes(StandardCharsets.UTF_8))
      val action = Run(Commands.Compile(List(RootProject), incremental = true))
      val state2 = TestUtil.blockingExecute(action, state)
      assertEquals(2.toLong, logger.compilingInfos.size.toLong)
      assertEquals(logger.errors.size.toLong, 3.toLong)
      ensureCompilationInAllTheBuild(state)

      // Rollback to previous source and force a compile, we shouldn't trigger another compile
      Files.write(sourceA.underlying, Sources.`A.scala`.getBytes(StandardCharsets.UTF_8))
      val noopAction = Run(Commands.Compile(List(RootProject), incremental = true))
      val noopState = TestUtil.blockingExecute(noopAction, state2)
      assertEquals(2.toLong, logger.compilingInfos.size.toLong)
      ensureCompilationInAllTheBuild(state)
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(
      structures,
      dependencies,
      scalaInstance = scalaInstance,
      quiet = false
    )(_ => ())
  }

  @Test
  def failSequentialCompilation(): Unit = {
    object Sources {
      val `A.scala` = "package p0\nclass A extends NonExistentClass"
      val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
      val `C.scala` = "package p2\nimport p0.A\nclass C extends A"
    }

    val structure = Map(
      "A" -> Map("A.scala" -> Sources.`A.scala`),
      "B" -> Map("B.scala" -> Sources.`B.scala`),
      "C" -> Map("C.scala" -> Sources.`C.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map("B" -> Set("A"), "C" -> Set("A"))
    TestUtil.testState(structure, deps, userLogger = Some(logger)) { (state: State) =>
      // Check that this is a clean compile!
      val projects = state.build.projects
      assert(projects.forall(p => noPreviousAnalysis(p, state)))

      val projectA = getProject("A", state)
      val projectB = getProject("B", state)
      val action = Run(Commands.Compile(List("B", "C")))
      val compiledState = TestUtil.blockingExecute(action, state)
      Assert.assertFalse("Expected compilation error", compiledState.status.isOk)

      // Check that A failed to compile and that `C` was skipped
      val errors = logger.getMessagesAt(Some("error"))
      val compileErrors =
        errors.filter(_.contains("failed to compile")).sorted.mkString(System.lineSeparator())
      Assert.assertEquals(
        compileErrors,
        """'A' failed to compile.
          |'B' failed to compile.
          |'C' failed to compile.""".stripMargin
      )
    }
  }

  @Test
  def compileWithDotty080RC1(): Unit = {
    val logger = new RecordingLogger()
    val scalaInstance =
      ScalaInstance.resolve("ch.epfl.lamp", "dotty-compiler_0.8", "0.8.0-RC1", logger)
    val structures = Map(RootProject -> Map("Dotty.scala" -> ArtificialSources.`Dotty.scala`))
    checkAfterCleanCompilation(structures, Map.empty, scalaInstance = scalaInstance) { state =>
      ensureCompilationInAllTheBuild(state)
    }
  }

  @Test
  def writeAnalysisFileByDefault(): Unit = {
    val testProject = "with-resources"
    val logger = new RecordingLogger()
    val state = TestUtil.loadTestProject(testProject).copy(logger = logger)
    val action = Run(Commands.Compile(List(testProject)))
    val compiledState = TestUtil.blockingExecute(action, state)
    val persistOut = (msg: String) => compiledState.commonOptions.ngout.println(msg)
    val t = Tasks.persist(compiledState, persistOut)

    try TestUtil.await(FiniteDuration(7, TimeUnit.SECONDS))(t)
    catch { case t: Throwable => logger.dump(); throw t }

    val analysisOutFile = state.build.getProjectFor(testProject).get.analysisOut
    assertTrue(Files.exists(analysisOutFile.underlying))
  }

  @Test
  def cacheCompilerPluginClassloaders(): Unit = {
    val target = "whitelistJS"
    val logger = new RecordingLogger()
    val state = TestUtil.loadTestProject("compiler-plugin-whitelist").copy(logger = logger)
    val action = Run(Commands.Compile(List(target)))
    val compiledState = TestUtil.blockingExecute(action, state)
    def loggerInfos = logger.getMessagesAt(Some("info"))

    val targetMsg = "Bloop test plugin classloader: scala.reflect.internal.util.ScalaClassLoader"
    loggerInfos.find(_.contains(targetMsg)) match {
      case Some(found) =>
        val cleanCompile = Run(Commands.Clean(List(target)), Run(Commands.Compile(List(target))))
        val cleanCompiledState = TestUtil.blockingExecute(cleanCompile, compiledState)
        Assert.assertEquals(loggerInfos.count(_ == found), 2)
      case None => Assert.fail("Expected log by `bloop-test-plugin` about classloader id")
    }
  }

  @Test
  def testCascadeCompilation(): Unit = {
    /*
     *    I
     *    |\
     *    | \
     *    H  G
     *    |
     *    F
     */

    object Sources {
      val `F.scala` = "package p0\nclass F"
      val `H.scala` = "package p1\nimport p0.F\nclass H extends F"
      val `G.scala` = "package p3\ntrait G"
      val `I.scala` = "package p2\nimport p1.H\nimport p3.G\nclass I extends H with G"
    }

    val structure = Map(
      "F" -> Map("F.scala" -> Sources.`F.scala`),
      "H" -> Map("H.scala" -> Sources.`H.scala`),
      "I" -> Map("I.scala" -> Sources.`I.scala`),
      "G" -> Map("G.scala" -> Sources.`G.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map("I" -> Set("H", "G", "F"), "H" -> Set("F"))
    TestUtil.testState(structure, deps, userLogger = Some(logger)) { (state: State) =>
      val action = Run(Commands.Compile(List("F"), cascade = true))
      val compiledState = TestUtil.blockingExecute(action, state)
      Assert.assertTrue("Unexpected compilation error", compiledState.status.isOk)
      TestUtil.assertNoDiff(
        """Compiling F (1 Scala source)
          |Compiling G (1 Scala source)
          |Compiling H (1 Scala source)
          |Compiling I (1 Scala source)
          """.stripMargin,
        logger.compilingInfos.sorted.mkString(System.lineSeparator)
      )
    }
  }

  @Test
  def cancelCompilation(): Unit = {
    object Sources {
      val `SleepMacro.scala` =
        """
          |package macros
          |
          |import scala.reflect.macros.blackbox.Context
          |import scala.language.experimental.macros
          |
          |object SleepMacro {
          |  def sleep(): Unit = macro sleepImpl
          |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
          |    import c.universe._
          |    // Sleep for 5 seconds to give time to cancel compilation
          |    Thread.sleep(5000)
          |    reify { () }
          |  }
          |}
          |
        """.stripMargin
      val `User.scala` =
        """
          |package user
          |
          |object User extends App {
          |  macros.SleepMacro.sleep()
          |}
        """.stripMargin
    }

    val deps = Map("user" -> Set("macros"))
    val structure = Map(
      "macros" -> Map("SleepMacro.scala" -> Sources.`SleepMacro.scala`),
      "user" -> Map("User.scala" -> Sources.`User.scala`)
    )

    import bloop.engine.ExecutionContext
    val logger = new RecordingLogger
    val scalaJars = TestUtil.scalaInstance.allJars.map(AbsolutePath.apply)
    TestUtil.testState(structure, deps, userLogger = Some(logger), extraJars = scalaJars) { state =>
      val compileMacroProject = Run(Commands.Compile(List("macros")))
      val compiledMacrosState = TestUtil.blockingExecute(compileMacroProject, state)
      Assert.assertTrue(
        "Unexpected compilation error when compiling macros",
        compiledMacrosState.status.isOk
      )

      val compileUserProject = Run(Commands.Compile(List("user")))
      val handle: CancelableFuture[State] = TestUtil
        .interpreterTask(compileUserProject, compiledMacrosState)
        .runAsync(ExecutionContext.scheduler)
      ExecutionContext.scheduler.scheduleOnce(3, TimeUnit.SECONDS, new Runnable {
        override def run(): Unit = handle.cancel()
      })

      val finalState = {
        try scala.concurrent.Await.result(handle, scala.concurrent.duration.Duration(20, "s"))
        catch {
          case NonFatal(t) => handle.cancel(); throw t
          case i: InterruptedException => handle.cancel(); compiledMacrosState
        }
      }

      Assert.assertEquals(ExitStatus.CompilationError, finalState.status)
      TestUtil.assertNoDiff(
        "Cancelling compilation of user",
        logger.warnings.mkString(System.lineSeparator())
      )
    }
  }
}
