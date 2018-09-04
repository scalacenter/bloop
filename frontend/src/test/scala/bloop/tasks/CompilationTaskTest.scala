package bloop.tasks

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import org.junit.Test
import org.junit.Assert.{assertFalse, assertTrue, assertEquals}
import org.junit.experimental.categories.Category
import bloop.ScalaInstance
import bloop.cli.Commands
import bloop.config.Config
import bloop.engine.tasks.Tasks
import bloop.engine.{Run, State}
import bloop.exec.JavaEnv
import bloop.logging.{Logger, RecordingLogger}
import bloop.tasks.TestUtil.{
  RootProject,
  checkAfterCleanCompilation,
  getProject,
  hasPreviousResult,
  noPreviousResult
}

import scala.concurrent.duration.FiniteDuration

@Category(Array(classOf[bloop.FastTests]))
class CompilationTaskTest {
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
      val newState = TestUtil.blockingExecute(Run(Commands.Compile(RootProject)), state)
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
  def compileScalaAndJava(): Unit = {
    val logger = new RecordingLogger
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(
      RootProject -> Map("A.scala" -> "object A", "B.java" -> "public class B {}"))

    checkAfterCleanCompilation(
      structures,
      dependencies,
      scalaInstance = scalaInstance2124(logger),
      useSiteLogger = Some(logger),
      order = Config.JavaThenScala
    )(_ => ())

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

    val errors = TestUtil.errorsFromLogger(logger)
    assert(errors.size == 3)
    assert(errors.exists(_.contains("cannot find symbol")))
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
      val erroneousState = TestUtil.blockingExecute(Run(Commands.Compile(RootProject)), newState)
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
      assertEquals(logger.compilingInfos.size.toLong, 3.toLong)

      val rootProject = state.build.getProjectFor(RootProject).get
      val sourceC = rootProject.sources.head.resolve("C.scala").underlying
      assert(Files.exists(sourceC))
      Files.write(sourceC, "package p2; class C".getBytes)

      val action = Run(Commands.Compile(RootProject, incremental = true))
      val state2 = TestUtil.blockingExecute(action, state)

      assertTrue(state2.status.isOk)
      ensureCompilationInAllTheBuild(state2)
      assertEquals(logger.compilingInfos.size.toLong, 4.toLong)
    }
  }

  @Test
  def unnecessaryProjectsAreNotCompiled(): Unit = {
    val projectsStructures = Map(
      "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
      RootProject -> Map("C.scala" -> ArtificialSources.`C2.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent"))
    checkAfterCleanCompilation(projectsStructures, dependencies, quiet = true) { (state: State) =>
      // The unrelated project should not have been compiled
      assertTrue(s"Project `unrelated` was compiled",
                 noPreviousResult(getProject("unrelated", state), state))
      assertTrue(s"Project `parent` was not compiled",
                 hasPreviousResult(getProject("parent", state), state))
      assertTrue(s"Project `RootProject` was not compiled",
                 hasPreviousResult(getProject(RootProject, state), state))
    }
  }

  @Test
  def noResultWhenCompilationFails(): Unit = {
    val projectsStructure = Map(RootProject -> Map("Error.scala" -> "iwontcompile"))
    checkAfterCleanCompilation(projectsStructure, Map.empty, failure = true) { (state: State) =>
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} has a compilation result", noPreviousResult(p, state))
      }
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
      assertTrue(s"Project `unrelated` was compiled",
                 noPreviousResult(getProject("unrelated", state), state))
      assertTrue(s"Project `parent` was not compiled",
                 hasPreviousResult(getProject("parent", state), state))
      assertTrue(s"Project `RootProject` was not compiled",
                 hasPreviousResult(getProject(RootProject, state), state))
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
    val deps = Map(RootProject -> Set("A", "B", "C", "D"),
                   "B" -> Set("A"),
                   "C" -> Set("A"),
                   "D" -> Set("B", "C"))
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      assertEquals(logger.compilingInfos.size.toLong, 5.toLong)
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
    val deps = Map(RootProject -> Set("A", "B", "C", "D"),
                   "B" -> Set("A"),
                   "C" -> Set("B", "A"),
                   "D" -> Set("B", "A"))
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      assertEquals(logger.compilingInfos.size.toLong, 5.toLong)
      ensureCompilationInAllTheBuild(state)
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(structures,
                               dependencies,
                               scalaInstance = scalaInstance,
                               quiet = false)(_ => ())
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

    val scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance
    val javaEnv: JavaEnv = JavaEnv.default
    TestUtil.withState(structure, deps, scalaInstance = scalaInstance, javaEnv = javaEnv) {
      (state0: State) =>
        val state = state0.copy(logger = logger)
        // Check that this is a clean compile!
        val projects = state.build.projects
        assert(projects.forall(p => noPreviousResult(p, state)))
        val projectA = getProject("A", state)
        val projectB = getProject("B", state)
        val action = Run(Commands.Compile("B"), Run(Commands.Compile("C")))
        val compiledState = TestUtil.blockingExecute(action, state)

        // Check that A failed to compile and that `C` was skipped
        val msgs = logger.getMessages
        assert(msgs.exists(m => m._1 == "error" && m._2.contains("'A' failed to compile.")))
        val targetMsg = s"Skipping compilation of project 'C'; dependent 'A' failed to compile."
        assert(msgs.exists(m => m._1 == "warn" && m._2.contains(targetMsg)))
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
    val action = Run(Commands.Compile(testProject))
    val compiledState = TestUtil.blockingExecute(action, state)
    val t = Tasks.persist(compiledState)

    try TestUtil.await(FiniteDuration(7, TimeUnit.SECONDS))(t)
    catch { case t: Throwable => logger.dump(); throw t }

    val analysisOutFile = state.build.getProjectFor(testProject).get.analysisOut
    assertTrue(Files.exists(analysisOutFile.underlying))
  }

  def ensureCompilationInAllTheBuild(state: State): Unit = {
    state.build.projects.foreach { p =>
      assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
    }
  }
}
