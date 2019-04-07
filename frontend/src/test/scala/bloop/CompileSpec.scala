package bloop

import bloop.config.Config
import bloop.io.{AbsolutePath, RelativePath, Paths => BloopPaths}
import bloop.logging.RecordingLogger
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Feedback, Run, State, ExecutionContext}
import bloop.engine.caches.ResultsCache
import bloop.util.{TestProject, TestUtil, BuildUtil}

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.CancelableFuture

object CompileSpec extends bloop.testing.BaseSuite {
  // TODO: Enable this before next stable release
  ignore("compile project for latest supported Scala versions") {
    def compileProjectFor(scalaVersion: String): Task[Unit] = Task {
      TestUtil.withinWorkspace { workspace =>
        val sources = List(
          """/main/scala/Foo.scala
            |class Foo
          """.stripMargin
        )
        def jarsForScalaVersion(version: String, logger: RecordingLogger) = {
          ScalaInstance
            .resolve("org.scala-lang", "scala-compiler", version, logger)(
              ExecutionContext.ioScheduler
            )
            .allJars
            .map(AbsolutePath(_))
        }

        val logger = new RecordingLogger(ansiCodesSupported = false)
        val jars = jarsForScalaVersion(scalaVersion, logger)
        val `A` =
          TestProject(workspace, "a", sources, scalaVersion = Some(scalaVersion), jars = jars)
        val projects = List(`A`)
        val state = loadState(workspace, projects, logger)
        val compiledState = state.compile(`A`)
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
      }
    }

    val `2.10.7` = compileProjectFor("2.10.6")
    val `2.11.11` = compileProjectFor("2.11.11")
    val `2.12.8` = compileProjectFor("2.12.8")
    val all = List(`2.10.7`, `2.11.11`, `2.12.8`)
    TestUtil.await(FiniteDuration(60, "s")) {
      Task.gatherUnordered(all).map(_ => ())
    }
  }

  test("compile a project twice with no input changes produces a no-op") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val secondCompiledState = compiledState.compile(`A`)
      assert(secondCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(compiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(secondCompiledState, projects)
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
      logger.debugs.foreach { debug =>
        assert(!debug.contains("Classpath hash changed"))
      }
    }
  }

  test("compile a project incrementally sourcing from an analysis file") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // This state loads the previous analysis from the persisted file
      val independentLogger = new RecordingLogger(ansiCodesSupported = false)
      val independentState = loadState(workspace, projects, independentLogger)
      assertSuccessfulCompilation(independentState, List(`A`), isNoOp = false)

      // Assert that it's a no-op even if we sourced from the analysis
      val secondCompiledState = independentState.compile(`A`)
      assert(secondCompiledState.status == ExitStatus.Ok)
      assertSuccessfulCompilation(secondCompiledState, List(`A`), isNoOp = true)
      assertValidCompilationState(secondCompiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(compiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(secondCompiledState, projects)
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
    }
  }

  test("compile project / clean / compile it again") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        s"""
           |Compiling a (1 Scala source)
        """.stripMargin
      )

      val cleanState = compiledState.clean(`A`)
      val secondCompiledState = cleanState.compile(`A`)
      assert(secondCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assertDifferentExternalClassesDirs(compiledState, secondCompiledState, projects)
      assertNonExistingInternalClassesDir(secondCompiledState)(compiledState, projects)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        s"""
           |Compiling a (1 Scala source)
           |Compiling a (1 Scala source)
        """.stripMargin
      )
    }
  }

  test("simulate an incremental compiler session") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |object A {
            |  def foo(s: String) = s.toString
            |}
          """.stripMargin

        val `Bar.scala` =
          """/Bar.scala
            |class Bar {
            |  println("Dummy class")
            |}
          """.stripMargin

        val `B.scala` =
          """/B.scala
            |object B {
            |  println(A.foo(""))
            |}
          """.stripMargin

        // Second (non-compiling) version of `A`
        val `A2.scala` =
          """/A.scala
            |object A {
            |  def foo(i: Int) = i.toString
            |}
          """.stripMargin

        // Third (compiling) version of `A`
        val `A3.scala` =
          """/A.scala
            |object A {
            |  def foo(s: String) = "asdfasdf"
            |}
          """.stripMargin
      }

      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`Bar.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val projects = List(`A`, `B`)
      val initialState = loadState(workspace, projects, logger)
      val compiledState = initialState.compile(`B`)

      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, List(`A`, `B`))
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling b (1 Scala source)""".stripMargin
      )

      assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A2.scala`))
      val secondCompiledState = compiledState.compile(`B`)
      assert(secondCompiledState.status == ExitStatus.CompilationError)
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
      assertValidCompilationState(secondCompiledState, List(`A`))
      // The internal classes dir for `A` should not exist, B failed to compile but A succeded
      assertNonExistingInternalClassesDir(secondCompiledState)(compiledState, List(`A`))

      assertInvalidCompilationState(
        secondCompiledState,
        List(`B`),
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )

      val targetBPath = TestUtil.universalPath("b/src/B.scala")
      assertNoDiff(
        logger.renderErrors(exceptContaining = "failed to compile"),
        s"""[E1] ${targetBPath}:2:17
           |     type mismatch;
           |      found   : String("")
           |      required: Int
           |     L2:   println(A.foo(""))
           |                         ^
           |${targetBPath}: L2 [E1]""".stripMargin
      )

      assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A.scala`))
      val thirdCompiledState = secondCompiledState.compile(`B`)
      assert(thirdCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(thirdCompiledState, List(`A`, `B`))
      assertSameExternalClassesDirs(compiledState, thirdCompiledState, projects)

      /*
       * Scenario: class files get removed from external classes dir.
       * Expected: compilation succeeds (no-op) and external classes dir is repopulated.
       */

      BloopPaths.delete(AbsolutePath(`B`.config.classesDir))
      val fourthCompiledState = thirdCompiledState.compile(`B`)
      assert(fourthCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(fourthCompiledState, List(`A`, `B`))
      assertSameExternalClassesDirs(compiledState, fourthCompiledState, projects)

      /*
       * Scenario: one class file is modified in the external classes dir and a source file changes.
       * Expected: incremental compilation succeeds and external classes dir is repopulated.
       */

      writeFile(`A`.externalClassFileFor("Bar.class"), "incorrect class file contents")
      assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A3.scala`))
      val fifthCompiledState = fourthCompiledState.compile(`B`)
      assert(fifthCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(fifthCompiledState, List(`A`, `B`))
      assertIsFile(`A`.externalClassFileFor("Bar.class"))

      /*
       * Scenario: a classes directory of the last successful result is removed.
       * Expected: we use an empty result instead.
       */

      BloopPaths.delete(fifthCompiledState.getLastClassesDir(`A`).get)
      BloopPaths.delete(fifthCompiledState.getLastClassesDir(`B`).get)
      val sixthCompiledState = fifthCompiledState.compile(`B`)
      assert(sixthCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(sixthCompiledState, List(`A`, `B`))

      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling b (1 Scala source)
          |Compiling a (1 Scala source)
          |Compiling b (1 Scala source)
          |Compiling a (1 Scala source)
          |Compiling a (1 Scala source)
          |Compiling a (2 Scala sources)
          |Compiling b (1 Scala source)""".stripMargin
      )

      // Check that initial classes directory doesn't exist either
      assertNonExistingInternalClassesDir(secondCompiledState)(compiledState, List(`A`))
      // There should only be three classes dirs: empty-a, current classes dir and external
      assert(list(workspace.resolve("target").resolve("a")).size == 3)
    }
  }

  test("compile a build with diamond shape and check basic compilation invariants") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |package p0
            |class A
          """.stripMargin

        val `B.scala` =
          """/B.scala
            |package p1
            |class B extends p0.A
          """.stripMargin

        val `C.scala` =
          """/C.scala
            |package p2
            |class C extends p0.A
          """.stripMargin
        val `D.scala` =
          """/D.scala
            |package p3
            |trait D
          """.stripMargin
        val `E.scala` =
          """/E.scala
            |package p4
            |import p2.C
            |object E extends p1.B with p3.D
          """.stripMargin
        val `F.scala` =
          """/F.scala
            |package p5
            |import p3.NotFound
            |class F
          """.stripMargin
      }

      val `Empty` = TestProject(workspace, "empty", Nil)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`), List(`Empty`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))
      val `C` = TestProject(workspace, "c", List(Sources.`C.scala`), List(`A`))
      val `D` = TestProject(workspace, "d", List(Sources.`D.scala`), List(`B`, `C`))
      val `E` = TestProject(workspace, "e", List(Sources.`E.scala`), List(`A`, `B`, `C`, `D`))
      val `F` = TestProject(workspace, "f", List(Sources.`F.scala`), List(`A`, `B`, `C`, `D`))

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val projects = List(`A`, `B`, `C`, `D`, `E`, `F`)
      val initialState = {
        // Reproduce and test https://github.com/scalacenter/bloop/issues/708
        val configDir = TestProject.populateWorkspace(workspace, projects ++ List(`Empty`))
        assertIsFile(configDir.resolve("empty.json"))
        Files.delete(configDir.resolve("empty.json").underlying)

        new TestState(
          TestUtil.loadTestProject(configDir.underlying, logger, false)
        )
      }

      val compiledState = initialState.compile(`E`)
      assert(compiledState.status == ExitStatus.Ok)

      // Only the build graph of `E` is compiled successfully
      assertValidCompilationState(compiledState, List(`A`, `B`, `C`, `D`, `E`))
      assertEmptyCompilationState(compiledState, List(`F`))
      assertNoDiff(
        logger.compilingInfos.sorted.mkString(System.lineSeparator),
        """Compiling a (1 Scala source)
          |Compiling b (1 Scala source)
          |Compiling c (1 Scala source)
          |Compiling d (1 Scala source)
          |Compiling e (1 Scala source)""".stripMargin
      )
      assertNoDiff(
        logger.warnings.sorted.mkString(System.lineSeparator),
        Feedback.detectMissingDependencies(`A`.config.name, List(`Empty`.config.name)).get
      )
    }
  }

  test("compile java code depending on scala code") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |package a
            |object A {
            |  val HelloWorld: String = "Hello World!"
            |}""".stripMargin
        val `B.java` =
          """/B.java
            |package b;
            |import a.A$;
            |public class B {
            |  public void entrypoint(String[] args) {
            |    A$ a = A$.MODULE$;
            |    System.out.println(a.HelloWorld());
            |  }  
            |}""".stripMargin
        val `C.scala` =
          """/C.scala
            |package b
            |object C {
            |  println(a.A.HelloWorld)
            |  println((new B).entrypoint(Array()))
            |}""".stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.java`, Sources.`C.scala`), List(`A`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`B`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }

  test("don't compile after renaming a class and not its references in the same project") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/main/scala/Foo.scala
            |class Foo
          """.stripMargin

        val `Bar.scala` =
          """/main/scala/Bar.scala
            |class Bar {
            |  val foo: Foo = new Foo
            |}
          """.stripMargin

        val `Foo2.scala` =
          """/main/scala/Foo.scala
            |class Foo2
          """.stripMargin

        val `Bar2.scala` =
          """/main/scala/Bar.scala
            |class Bar {
            |  val foo: Foo2 = new Foo2
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo.scala`, Sources.`Bar.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      val compiledStateBackup = compiledState.backup
      assertValidCompilationState(compiledState, projects)

      // Write nir and sjsir files to ensure the class file manager invalidates them properly
      def createFakeCompileProducts(
          lastClassesDir: AbsolutePath,
          clientDir: AbsolutePath,
          classFileName: String
      ): Unit = {
        def copy(origin: AbsolutePath, target: AbsolutePath): Unit = {
          import java.nio.file.StandardCopyOption
          Files.copy(
            origin.underlying,
            target.underlying,
            StandardCopyOption.COPY_ATTRIBUTES,
            StandardCopyOption.REPLACE_EXISTING
          )
          ()
        }

        assertIsFile(lastClassesDir.resolve(classFileName))
        val fileName = classFileName.stripSuffix(".class")
        val sjsirFile = lastClassesDir.resolve(RelativePath(s"${fileName}.sjsir"))
        val externalSjsirFile = clientDir.resolve(RelativePath(s"${fileName}.sjsir"))
        writeFile(sjsirFile, "emulating sjsir file")
        copy(sjsirFile, externalSjsirFile)
        val nirFile = lastClassesDir.resolve(RelativePath(s"${fileName}.nir"))
        val externalNirFile = clientDir.resolve(RelativePath(s"${fileName}.nir"))
        writeFile(nirFile, "emulating nir file")
        copy(nirFile, externalNirFile)
        val tastyFile = lastClassesDir.resolve(RelativePath(s"${fileName}.tasty"))
        val externalTastyFile = clientDir.resolve(RelativePath(s"${fileName}.tasty"))
        writeFile(tastyFile, "emulating tasty file")
        copy(tastyFile, externalTastyFile)
        ()
      }

      val clientDirA = compiledState.getClientExternalDir(`A`)
      val lastClassesDirA = compiledState.getLastClassesDir(`A`).get
      createFakeCompileProducts(lastClassesDirA, clientDirA, "Foo.class")
      createFakeCompileProducts(lastClassesDirA, clientDirA, "Bar.class")

      // #2: Compiler after renaming `Foo` to `Foo2`, which should make `Bar` fail in second cycle
      assertIsFile(writeFile(`A`.srcFor("main/scala/Foo.scala"), Sources.`Foo2.scala`))
      val secondCompiledState = compiledState.compile(`A`)
      assert(ExitStatus.CompilationError == secondCompiledState.status)
      assertInvalidCompilationState(
        secondCompiledState,
        List(`A`),
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )

      val targetBar = TestUtil.universalPath("a/src/main/scala/Bar.scala")
      assertNoDiff(
        s"""
           |[E2] ${targetBar}:2:22
           |     not found: type Foo
           |     L2:   val foo: Foo = new Foo
           |                              ^
           |[E1] ${targetBar}:2:12
           |     not found: type Foo
           |     L2:   val foo: Foo = new Foo
           |                    ^
           |${targetBar}: L2 [E1], L2 [E2]
          """.stripMargin,
        logger.renderErrors(exceptContaining = "failed to compile")
      )

      assertIsFile(writeFile(`A`.srcFor("main/scala/Bar.scala"), Sources.`Bar2.scala`))
      val thirdCompiledState = secondCompiledState.compile(`A`)
      assert(thirdCompiledState.status == ExitStatus.Ok)

      assertValidCompilationState(thirdCompiledState, projects)
      assertDifferentExternalClassesDirs(thirdCompiledState, compiledStateBackup, projects)
      // Checks that we remove `Foo.class` from the external classes dir which is critical
      assertNonExistingCompileProduct(thirdCompiledState, `A`, RelativePath("Foo.class"))
      assertNonExistingCompileProduct(thirdCompiledState, `A`, RelativePath("Foo.sjsir"))
      assertNonExistingCompileProduct(thirdCompiledState, `A`, RelativePath("Foo.nir"))
      assertNonExistingCompileProduct(thirdCompiledState, `A`, RelativePath("Foo.tasty"))
      assertNonExistingInternalClassesDir(thirdCompiledState)(compiledState, projects)
    }
  }

  test("don't compile after renaming a class and not its references in a dependent project") {
    // Checks bloop is invalidating classes + propagating them to *transitive* dependencies
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/main/scala/Foo.scala
            |class Foo
          """.stripMargin

        val `Foo2.scala` =
          """/main/scala/Foo.scala
            |class Foo2
          """.stripMargin

        val `Baz.scala` =
          """/main/scala/Baz.scala
            |class Baz
          """.stripMargin

        val `Single.scala` =
          """/main/scala/Single.scala
            |class Single
          """.stripMargin

        val `Bar.scala` =
          """/main/scala/Bar.scala
            |class Bar {
            |  val foo: Foo = new Foo
            |}
          """.stripMargin

        val `Bar2.scala` =
          """/main/scala/Bar.scala
            |class Bar {
            |  val foo: Foo2 = new Foo2
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo.scala`, Sources.`Baz.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`Single.scala`), List(`A`))
      val `C` = TestProject(workspace, "c", List(Sources.`Bar.scala`), List(`B`))
      val projects = List(`A`, `B`, `C`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`C`)
      assertValidCompilationState(compiledState, projects)

      // #2: Compiler after renaming `Foo` to `Foo2`, which should make `Bar` fail in second cycle
      assertIsFile(writeFile(`A`.srcFor("main/scala/Foo.scala"), Sources.`Foo2.scala`))
      val secondCompiledState = compiledState.compile(`C`)
      assert(ExitStatus.CompilationError == secondCompiledState.status)
      assertInvalidCompilationState(
        secondCompiledState,
        List(`A`, `B`, `C`),
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )

      val targetBar = TestUtil.universalPath("c/src/main/scala/Bar.scala")
      assertNoDiff(
        s"""
           |[E2] ${targetBar}:2:22
           |     not found: type Foo
           |     L2:   val foo: Foo = new Foo
           |                              ^
           |[E1] ${targetBar}:2:12
           |     not found: type Foo
           |     L2:   val foo: Foo = new Foo
           |                    ^
           |${targetBar}: L2 [E1], L2 [E2]
          """.stripMargin,
        logger.renderErrors(exceptContaining = "failed to compile")
      )

      assertIsFile(writeFile(`C`.srcFor("main/scala/Bar.scala"), Sources.`Bar2.scala`))
      val thirdCompiledState = secondCompiledState.compile(`C`)
      assert(thirdCompiledState.status == ExitStatus.Ok)
      // Checks that we remove `Foo.class` from the external classes dir which is critical
      assertValidCompilationState(thirdCompiledState, projects)
      assertNonExistingCompileProduct(thirdCompiledState, `A`, RelativePath("Foo.class"))
    }
  }

  test("report java errors when `JavaThenScala` is enabled") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A
          """.stripMargin
        val `B.java` =
          """/B.java
            |public class B extends A {}
          """.stripMargin
      }

      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`A.scala`, Sources.`B.java`),
        order = Config.JavaThenScala
      )

      val projects = List(`A`)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val initialState = loadState(workspace, projects, logger)
      val compiledState = initialState.compile(`A`)
      assert(compiledState.status == ExitStatus.CompilationError)
      assertInvalidCompilationState(
        compiledState,
        projects,
        existsAnalysisFile = false,
        hasPreviousSuccessful = false,
        hasSameContentsInClassesDir = true
      )

      val targetB = TestUtil.universalPath("a/src/B.java")
      val cannotFindSymbolError: String = {
        s"""[E1] ${targetB}:1
           |     cannot find symbol
           |       symbol: class A
           |${targetB}: L1 [E1]""".stripMargin
      }

      val cannotFindSymbolError2: String = {
        s"""[E1] ${targetB}:1
           |      error: cannot find symbol
           |${targetB}: L1 [E1]""".stripMargin
      }

      assertDiagnosticsResult(compiledState.getLastResultFor(`A`), 1)
      import bloop.testing.DiffAssertions
      try {
        assertNoDiff(
          logger.renderErrors(exceptContaining = "failed to compile"),
          cannotFindSymbolError
        )
      } catch {
        case _: DiffAssertions.TestFailedException =>
          assertNoDiff(
            logger.renderErrors(exceptContaining = "failed to compile"),
            cannotFindSymbolError2
          )
      }
    }
  }

  test("detect Scala syntactic errors") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/Foo.scala
          |class Foo {
          |  al foo: String = 1
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.CompilationError)
      assertInvalidCompilationState(
        compiledState,
        projects,
        existsAnalysisFile = false,
        hasPreviousSuccessful = false,
        hasSameContentsInClassesDir = true
      )

      val targetFoo = TestUtil.universalPath("a/src/Foo.scala")
      assertDiagnosticsResult(compiledState.getLastResultFor(`A`), 1)
      assertNoDiff(
        logger.renderErrors(exceptContaining = "failed to compile"),
        s"""[E1] ${targetFoo}:2:18
           |     ';' expected but '=' found.
           |     L2:   al foo: String = 1
           |                          ^
           |${targetFoo}: L2 [E1]""".stripMargin
      )
    }
  }

  test("detect invalid Scala compiler flags") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/Foo.scala
          |object Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources, scalacOptions = List("-Ytyper-degug"))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)

      assert(compiledState.status == ExitStatus.CompilationError)
      assertInvalidCompilationState(
        compiledState,
        projects,
        existsAnalysisFile = false,
        hasPreviousSuccessful = false,
        hasSameContentsInClassesDir = true
      )

      assertDiagnosticsResult(compiledState.getLastResultFor(`A`), 1)
      assertNoDiff(
        logger.renderErrors(exceptContaining = "failed to compile"),
        """bad option: '-Ytyper-degug'""".stripMargin
      )
    }
  }

  test("cascade compilation compiles only a strict subset of targets") {
    TestUtil.withinWorkspace { workspace =>
      /*
       *  Read build graph dependencies from top to bottom.
       *
       *    I
       *    |\
       *    | \
       *    H  G
       *    |
       *    F
       */

      object Sources {
        val `F.scala` =
          """/F.scala
            |package p0
            |class F
          """.stripMargin
        val `H.scala` =
          """/H.scala
            |package p1
            |class H extends p0.F
          """.stripMargin
        val `G.scala` =
          """/G.scala
            |package p3
            |trait G
          """.stripMargin
        val `I.scala` =
          """/I.scala
            |package p2
            |class I extends p1.H with p3.G
          """.stripMargin
      }

      val `F` = TestProject(workspace, "F", List(Sources.`F.scala`))
      val `G` = TestProject(workspace, "G", List(Sources.`G.scala`))
      val `H` = TestProject(workspace, "H", List(Sources.`H.scala`), List(`F`))
      val `I` = TestProject(workspace, "I", List(Sources.`I.scala`), List(`H`, `G`, `F`))

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val projects = List(`F`, `G`, `H`, `I`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.cascadeCompile(`F`)

      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
      assertNoDiff(
        logger.compilingInfos.sorted.mkString(System.lineSeparator),
        """Compiling F (1 Scala source)
          |Compiling G (1 Scala source)
          |Compiling H (1 Scala source)
          |Compiling I (1 Scala source)
          """.stripMargin
      )
    }
  }

  test("cancel slow compilation") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    BuildUtil.testSlowBuild(logger) { build =>
      val state = new TestState(build.state)
      val compiledMacrosState = state.compile(build.macroProject)
      assert(compiledMacrosState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledMacrosState, List(build.macroProject))

      val projects = List(build.macroProject, build.userProject)
      val backgroundCompiledUserState =
        compiledMacrosState.compileHandle(build.userProject)

      val waitTimeToCancel = {
        val randomMs = scala.util.Random.nextInt(1000)
        (if (randomMs < 250) 250 else randomMs).toLong
      }

      ExecutionContext.ioScheduler.scheduleOnce(
        waitTimeToCancel,
        TimeUnit.MILLISECONDS,
        new Runnable {
          override def run(): Unit = backgroundCompiledUserState.cancel()
        }
      )

      import scala.util.control.NonFatal
      val compiledUserState = {
        // There are two macro calls in two different sources, cancellation must avoid one
        try Await.result(backgroundCompiledUserState, Duration(2950, "ms"))
        catch {
          case NonFatal(t) => backgroundCompiledUserState.cancel(); throw t
          case i: InterruptedException => backgroundCompiledUserState.cancel(); compiledMacrosState
        }
      }

      assert(compiledUserState.status == ExitStatus.CompilationError)
      assertCancelledCompilation(compiledUserState, List(build.userProject))
      assertNoDiff(
        logger.warnings.mkString(System.lineSeparator()),
        "Cancelling compilation of user"
      )
    }
  }

  ignore("compiler plugins are cached automatically") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        // A slight modification of the original `App.scala` to trigger incremental compilation
        val `App2.scala` =
          """package hello
            |
            |object App {
            |  def main(args: Array[String]): Unit = {
            |    println("Whitelist application was compiled successfully v2.0")
            |  }
            |}
        """.stripMargin

        val `App3.scala` =
          """package hello
            |
            |object App {
            |  def main(args: Array[String]): Unit = {
            |    println("Whitelist application was compiled successfully v3.0")
            |  }
            |}
        """.stripMargin
      }

      // This test is more manual than usual because we use a build defined in resources
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val build = loadBuildFromResources("compiler-plugin-whitelist", workspace, logger)

      val whitelistProject = build.projectFor("whitelistJS")
      val compiledState = build.state.compile(whitelistProject)

      val previousCacheHits = logger.debugs.count(_.startsWith("Cache hit true")).toLong
      val targetMsg = "Bloop test plugin classloader: scala.reflect.internal.util.ScalaClassLoader"

      logger.infos.find(_.contains(targetMsg)) match {
        case Some(found) =>
          val `App.scala` = whitelistProject.srcFor("hello/App.scala")
          assertIsFile(writeFile(`App.scala`, Sources.`App2.scala`))
          val secondCompiledState = compiledState.compile(whitelistProject)

          // The recompilation forces the compiler to show the hashcode of plugin classloader
          val foundMessages = logger.infos.count(_ == found)
          assert(foundMessages == 2)

          // Ensure that the next time we compile we hit the cache that tells us to whitelist or not
          val totalCacheHits = logger.debugs.count(_.startsWith("Cache hit true")).toLong
          assert((totalCacheHits - previousCacheHits) == 16)

          // Disable the cache manually by changing scalac options in configuration file
          val (newWhitelistProject, stateWithDisabledPluginClassloader) = {
            val remainingProjects = build.projects.filter(_ != whitelistProject)
            val currentOptions = whitelistProject.config.scala.map(_.options).getOrElse(Nil)
            val scalacOptions = "-Ycache-plugin-class-loader:none" :: currentOptions
            val newScala = whitelistProject.config.scala.map(_.copy(options = scalacOptions))
            val newWhitelistProject =
              new TestProject(whitelistProject.config.copy(scala = newScala), None)
            val newProjects = newWhitelistProject :: remainingProjects
            val configDir = populateWorkspace(build, List(newWhitelistProject))
            newWhitelistProject -> loadState(workspace, newProjects, logger)
          }

          // Force 3rd and last incremental compiler iteration to check the hash changes
          assertIsFile(writeFile(`App.scala`, Sources.`App3.scala`))
          stateWithDisabledPluginClassloader.compile(newWhitelistProject)
          assert(logger.infos.count(_.contains(targetMsg)) == 3)
          assert(logger.infos.count(_ == found) == 2)

        case None => fail("Expected log by `bloop-test-plugin` about classloader id")
      }
    }
  }

  ignore("check that we report rich diagnostics in the CLI when -Yrangepos") {
    // From https://github.com/scalacenter/bloop/issues/787
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/main/scala/A.scala
            |object A {
            |  "".lengthCompare("1".substring(0))
            |
            |  // Make range pos multi-line to ensure range pos doesn't work here
            |  "".lengthCompare("1".
            |    substring(0))
            |}""".stripMargin
      }

      val options = List("-Yrangepos")
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`), scalacOptions = options)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val state = loadState(workspace, List(`A`), logger)
      val compiledState = state.compile(`A`)

      val targetA = TestUtil.universalPath("a/src/main/scala/A.scala")
      assert(compiledState.status == ExitStatus.CompilationError)
      assertNoDiff(
        logger.errors.mkString(System.lineSeparator()),
        s"""
           |[E2] ${targetA}:6:14
           |     type mismatch;
           |      found   : String
           |      required: Int
           |     L6:     substring(0))
           |                      ^
           |[E1] ${targetA}:2:33
           |     type mismatch;
           |      found   : String
           |      required: Int
           |     L2:   "".lengthCompare("1".substring(0))
           |                            ^^^^^^^^^^^^^^^^
           |${targetA}: L2 [E1], L6 [E2]
           |'a' failed to compile.""".stripMargin
      )
    }
  }

  ignore("check positions reporting in adjacent diagnostics") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |object Dep {
            |  val a1: Int = ""
            |  val a2: Int = ""
            |}""".stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.CompilationError)
      assert(logger.errors.size == 4)

      val targetA = TestUtil.universalPath("a/src/A.scala")
      assertNoDiff(
        logger.errors.mkString(System.lineSeparator),
        s"""[E2] ${targetA}:3:17
           |     type mismatch;
           |      found   : String("")
           |      required: Int
           |     L3:   val a2: Int = ""
           |                         ^
           |[E1] ${targetA}:2:17
           |     type mismatch;
           |      found   : String("")
           |      required: Int
           |     L2:   val a1: Int = ""
           |                         ^
           |${targetA}: L2 [E1], L3 [E2]
           |'a' failed to compile.""".stripMargin
      )
    }
  }
}
