package bloop

import bloop.config.Config
import bloop.io.{AbsolutePath, RelativePath, Paths => BloopPaths}
import bloop.logging.RecordingLogger
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Feedback, Run, State, ExecutionContext}
import bloop.engine.caches.ResultsCache
import bloop.util.{TestProject, TestUtil, BuildUtil}

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.misc.NonFatal
import monix.execution.CancelableFuture
import bloop.cli.CommonOptions
import bloop.engine.NoPool
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import bloop.testing.DiffAssertions

object CompileSpec extends bloop.testing.BaseSuite {
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

  test("compile a project, delete an analysis and then write it back during a no-op compilation") {
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

      val analysisFile = compiledState.getProjectFor(`A`).analysisOut
      assert(analysisFile.exists)
      Files.delete(analysisFile.underlying)

      val secondCompiledState = compiledState.compile(`A`)
      assert(secondCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assert(analysisFile.exists)

      // Logger should contain only the log from the previous compile, as this is a no-op
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        s"""
           |Compiling a (1 Scala source)
        """.stripMargin
      )
    }
  }

  test("compile build incrementally sourcing from an analysis file") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A
          """.stripMargin
        val `B.scala` =
          """/B.scala
            |class B extends A
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`B`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // This state loads the previous analysis from the persisted file
      val independentLogger = new RecordingLogger(ansiCodesSupported = false)
      val independentState = loadState(workspace, projects, independentLogger)
      assertSuccessfulCompilation(independentState, projects, isNoOp = false)

      // Assert that it's a no-op even if we sourced from the analysis
      val secondCompiledState = independentState.compile(`B`)
      assert(secondCompiledState.status == ExitStatus.Ok)
      assertSuccessfulCompilation(secondCompiledState, projects, isNoOp = true)
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
      // The internal classes dir for `A` should not exist, B failed to compile but A succeeded
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
        logger.renderErrors(exceptContaining = "Failed to compile"),
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

      def listClassesDirs(dir: AbsolutePath) =
        bloop.io.Paths.list(dir).filter(_.isDirectory).map(ap => ap.toRelative(dir).syntax)

      // There should only be one external classes dir: the one coming from the CLI session
      assert(listClassesDirs(`A`.clientClassesRootDir).size == 1)
      val internalClassesRootDir =
        CompileOutPaths.createInternalClassesRootDir(AbsolutePath(`A`.config.out))
      assert(listClassesDirs(internalClassesRootDir).size == 1)
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

  test("compile after moving a class across project + invalidating symbol in a dependent project") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/Foo.scala
            |import Enrichments._
            |class Foo
          """.stripMargin

        // A dummy file to avoid surpassing the 50% changed sources and trigger a full compile
        val `Dummy.scala` =
          """/Dummy.scala
            |class Dummy
          """.stripMargin

        // A second dummy file
        val `Dummy2.scala` =
          """/Dummy2.scala
            |class Dummy2
          """.stripMargin

        val `Enrichments.scala` =
          """/Enrichments.scala
            |object Enrichments {}
          """.stripMargin

        val `Enrichments2.scala` =
          """/Enrichments.scala
            |object Enrichments {
            |  implicit class XtensionString(str: String) {
            |    def isGreeting: Boolean = str == "Hello world"
            |  }
            |}
          """.stripMargin

        val `Bar.scala` =
          """/Bar.scala
            |import Enrichments._
            |class Bar {
            |  val foo: Foo = new Foo
            |  def hello: String = "hello"
            |  println(hello)
            |}
          """.stripMargin

        val `Baz.scala` =
          """/Baz.scala
            |import Enrichments._
            |class Baz extends Bar
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo.scala`, Sources.`Enrichments.scala`))
      val sourcesB = List(
        Sources.`Bar.scala`,
        Sources.`Baz.scala`,
        Sources.`Dummy.scala`,
        Sources.`Dummy2.scala`
      )
      val `B` = TestProject(workspace, "b", sourcesB, List(`A`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`B`)
      val compiledStateBackup = compiledState.backup
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // Move `Bar.scala` from `B` to `A`; code still compiles
      Files.move(
        `B`.srcFor("Bar.scala").underlying,
        `A`.srcFor("Bar.scala", exists = false).underlying
      )

      val secondCompiledState = compiledState.compile(`B`)
      assertNoDiff(logger.errors.mkString(System.lineSeparator), "")
      assert(secondCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assertDifferentExternalClassesDirs(secondCompiledState, compiledStateBackup, projects)

      writeFile(`A`.srcFor("Enrichments.scala"), Sources.`Enrichments2.scala`)
      val thirdCompiledState = secondCompiledState.compile(`B`)
      assertNoDiff(logger.errors.mkString(System.lineSeparator), "")
      assert(thirdCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(thirdCompiledState, projects)
      assertDifferentExternalClassesDirs(thirdCompiledState, secondCompiledState, projects)
    }
  }

  test("don't compile after renaming a Scala class and not its references in the same project") {
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
        logger.renderErrors(exceptContaining = "Failed to compile")
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

  test("detect binary changes in Java within the same project") {
    def compileTestWithOrder(order: Config.CompileOrder): Unit = {
      TestUtil.withinWorkspace { workspace =>
        object Sources {
          val `Foo.java` =
            """/Foo.java
              |public class Foo {
              |  public String greeting() {
              |    return "Hello World";
              |  }
              |}
          """.stripMargin

          val `Bar.scala` =
            """/Bar.scala
              |class Bar {
              |  val foo: Foo = new Foo
              |  println(foo.greeting())
              |}
          """.stripMargin

          val `Foo2.java` =
            """/Foo.java
              |public class Foo {
              |  public String hello() {
              |    return "Hello World";
              |  }
              |}
          """.stripMargin

          val `Bar2.scala` =
            """/Bar.scala
              |class Bar {
              |  val foo: Foo = new Foo
              |  println(foo.hello())
              |}
          """.stripMargin
        }

        val logger = new RecordingLogger(ansiCodesSupported = false)
        val `A` = TestProject(
          workspace,
          "a",
          List(Sources.`Foo.java`, Sources.`Bar.scala`),
          order = order
        )
        val projects = List(`A`)
        val state = loadState(workspace, projects, logger)
        val compiledState = state.compile(`A`)
        val compiledStateBackup = compiledState.backup
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        // #2: Compiler after changing public API of `Foo`, which should make `Bar` fail
        assertIsFile(writeFile(`A`.srcFor("Foo.java"), Sources.`Foo2.java`))
        val secondCompiledState = compiledState.compile(`A`)
        assert(ExitStatus.CompilationError == secondCompiledState.status)
        assertInvalidCompilationState(
          secondCompiledState,
          List(`A`),
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )

        val targetBar = TestUtil.universalPath("a/src/Bar.scala")
        assertNoDiff(
          logger.renderErrors(exceptContaining = "Failed to compile"),
          s"""|[E1] ${targetBar}:3:15
              |     value greeting is not a member of Foo
              |     L3:   println(foo.greeting())
              |                       ^
              |${targetBar}: L3 [E1]
              |""".stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/Bar.scala"), Sources.`Bar2.scala`))
        val thirdCompiledState = secondCompiledState.compile(`A`)
        Predef.assert(thirdCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(thirdCompiledState, projects)
        assertDifferentExternalClassesDirs(thirdCompiledState, compiledStateBackup, projects)
        assertExistingCompileProduct(secondCompiledState, `A`, RelativePath("Foo.class"))
      }
    }

    compileTestWithOrder(Config.Mixed)
    compileTestWithOrder(Config.JavaThenScala)
  }

  test("invalidate Scala class files in javac forked and local compilation") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.java` =
          """/Foo.java
            |public class Foo {
            |  public String printHelloWorld() {
            |    Bar bar = new Bar();
            |    return bar.greeting();
            |  }
            |}
          """.stripMargin

        val `Bar.scala` =
          """/Bar.scala
            |class Bar {
            |  def greeting(): String = "Hello World"
            |}
          """.stripMargin

        val `Bar2.scala` =
          """/Bar.scala
            |class Bar2 {
            |  def bla(): String = "Hello World"
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`Foo.java`, Sources.`Bar.scala`),
        order = Config.ScalaThenJava
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      val compiledStateBackup = compiledState.backup
      Predef.assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      assertIsFile(writeFile(`A`.srcFor("Bar.scala"), Sources.`Bar2.scala`))
      val secondCompiledState = compiledState.compile(`A`)
      Predef.assert(ExitStatus.CompilationError == secondCompiledState.status)
      assertInvalidCompilationState(
        secondCompiledState,
        List(`A`),
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )

      val targetFoo = TestUtil.universalPath("a/src/Foo.java")
      val expectedMessage = {
        val supportsLocalCompilation =
          javax.tools.ToolProvider.getSystemJavaCompiler() != null
        if (supportsLocalCompilation) {
          s"""|[E2] ${targetFoo}:3
              |     cannot find symbol
              |       symbol:   class Bar
              |       location: class Foo
              |[E1] ${targetFoo}:3
              |     cannot find symbol
              |       symbol:   class Bar
              |       location: class Foo
              |${targetFoo}: L3 [E1], L3 [E2]
              |""".stripMargin
        } else {
          s"""|[E1] ${targetFoo}:3
              |      error: cannot access Bar
              |${targetFoo}: L3 [E1]
              |""".stripMargin
        }
      }

      assertNoDiff(
        logger.renderErrors(exceptContaining = "Failed to compile"),
        expectedMessage
      )
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
        logger.renderErrors(exceptContaining = "Failed to compile")
      )

      assertIsFile(writeFile(`C`.srcFor("main/scala/Bar.scala"), Sources.`Bar2.scala`))
      val thirdCompiledState = secondCompiledState.compile(`C`)
      assert(thirdCompiledState.status == ExitStatus.Ok)
      // Checks that we remove `Foo.class` from the external classes dir which is critical
      assertValidCompilationState(thirdCompiledState, projects)
      assertNonExistingCompileProduct(thirdCompiledState, `A`, RelativePath("Foo.class"))
    }
  }

  test("compile after moving class from one project to a dependent one") {
    // Checks bloop is invalidating classes + propagating them to *transitive* dependencies
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Dummy.scala` =
          """/main/scala/Dummy.scala
            |class Dummy
          """.stripMargin

        val `Foo.scala` =
          """/main/scala/Foo.scala
            |class Foo {
            |  def foo: String = ""
            |}
          """.stripMargin

        val `Bar.scala` =
          """/main/scala/Bar.scala
            |class Bar extends Foo
          """.stripMargin

        val `Baz.scala` =
          """/main/scala/Baz.scala
            |class Baz {
            |  val bar: Bar = new Bar
            |  def hello = println(bar.foo)
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Dummy.scala`))
      val `B` = TestProject(
        workspace,
        "b",
        List(
          Sources.`Foo.scala`,
          Sources.`Bar.scala`,
          Sources.`Baz.scala`
        ),
        List(`A`)
      )

      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`B`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      Files.move(
        `B`.srcFor("main/scala/Foo.scala").underlying,
        `A`.srcFor("main/scala/Foo.scala", exists = false).underlying
      )

      val compiledStateBackup = compiledState.backup

      // Compile first only `A`
      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, List(`A`))
      assertDifferentExternalClassesDirs(secondCompiledState, compiledStateBackup, `A`)

      // Then compile `B` to make sure right info from `A` is passed to `B` for invalidation
      val thirdCompiledState = secondCompiledState.compile(`B`)
      assertExitStatus(thirdCompiledState, ExitStatus.Ok)
      assertValidCompilationState(thirdCompiledState, List(`A`, `B`))

    /*
      assertInvalidCompilationState(
        secondCompiledState,
        List(`A`, `B`),
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
        logger.renderErrors(exceptContaining = "Failed to compile")
      )
     */
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
          logger.renderErrors(exceptContaining = "Failed to compile"),
          cannotFindSymbolError
        )
      } catch {
        case _: DiffAssertions.TestFailedException =>
          assertNoDiff(
            logger.renderErrors(exceptContaining = "Failed to compile"),
            cannotFindSymbolError2
          )
      }
    }
  }

  test("support -Xfatal-warnings internal implementation") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/Foo.scala
            |import Predef.assert
            |class Foo
            """.stripMargin
        val `Bar.scala` =
          """/Bar.scala
            |class Bar
            """.stripMargin
        val `Baz.scala` =
          """/Baz.scala
            |class Baz
            """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val sources = List(Sources.`Bar.scala`, Sources.`Foo.scala`, Sources.`Baz.scala`)
      val options = List("-Ywarn-unused", "-Xfatal-warnings")
      val `A` = TestProject(workspace, "a", sources, scalacOptions = options)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.CompilationError)
      // Despite error, compilation of project should be valid
      assertValidCompilationState(compiledState, projects)

      val targetFoo = TestUtil.universalPath("a/src/Foo.scala")
      assertNoDiff(
        logger.renderErrors(exceptContaining = "Failed to compile"),
        s"""|[E1] ${targetFoo}:1:15
            |     Unused import
            |     L1: import Predef.assert
            |         ^^^^^^^
            |""".stripMargin
      )
      assertDiagnosticsResult(
        compiledState.getLastResultFor(`A`),
        errors = 0,
        warnings = 1,
        expectFatalWarnings = true
      )
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
        logger.renderErrors(exceptContaining = "Failed to compile"),
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
        logger.renderErrors(exceptContaining = "Failed to compile"),
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

  test("compiler plugins are cached automatically") {
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

  test("check that we report rich diagnostics in the CLI when -Yrangepos") {
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
           |Failed to compile 'a'""".stripMargin
      )
    }
  }

  test("check positions reporting in adjacent diagnostics") {
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
           |Failed to compile 'a'""".stripMargin
      )
    }
  }

  test("don't compile build in two concurrent CLI clients") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )
      val testOut = new ByteArrayOutputStream()
      val options = CommonOptions.default.copy(out = new PrintStream(testOut))
      val `A` = TestProject(workspace, "a", sources)
      val configDir = TestProject.populateWorkspace(workspace, List(`A`))
      val compileArgs = Array("compile", "a", "--config-dir", configDir.syntax)
      val compileAction = Cli.parse(compileArgs, options)
      def runCompileAsync = Task.fork(Task.eval(Cli.run(compileAction, NoPool)))
      val runCompile = Task.gatherUnordered(List(runCompileAsync, runCompileAsync)).map(_ => ())
      Await.result(runCompile.runAsync(ExecutionContext.ioScheduler), FiniteDuration(10, "s"))

      val actionsOutput = new String(testOut.toByteArray(), StandardCharsets.UTF_8)

      val expected = actionsOutput
        .split(System.lineSeparator())
        .filterNot(_.startsWith("Compiled"))
        .map(msg => RecordingLogger.replaceTimingInfo(msg))
        .mkString(System.lineSeparator())
        .replaceAll("'(bloop-cli-.*)'", "'bloop-cli'")
        .replaceAll("'bloop-cli'", "???")

      try {
        assertNoDiff(
          expected,
          """Compiling a (1 Scala source)
            |Deduplicating compilation of a from cli client ??? (since ???
            |Compiling a (1 Scala source)
            |""".stripMargin
        )
      } catch {
        case _: DiffAssertions.TestFailedException =>
          assertNoDiff(
            expected,
            """
              |Deduplicating compilation of a from cli client ??? (since ???
              |Compiling a (1 Scala source)
              |Compiling a (1 Scala source)
              |""".stripMargin
          )
      }
    }
  }

  test("compile a project that redundantly lists an exact file as well as parent directory") {
    TestUtil.withinWorkspace { workspace =>
      val filename = "/main/scala/Foo.scala"
      val sources = List(
        s"""$filename
           |class Foo
           """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = {
        val base = TestProject(workspace, "a", sources)
        val srcs = base.config.sources match {
          case dir :: Nil => dir :: Paths.get(dir.toString, filename) :: Nil
          case other => fail(s"Expected exactly one source directory, got $other")
        }
        base.copy(config = base.config.copy(sources = srcs))
      }

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }

  test("compile Scala class after renaming a static member in a Java class") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A1.java` =
          """/A.java
            |public class A {
            |  public static void method() {}
            |  public static void dummy() {}
            |}
          """.stripMargin

        val `B1.java` =
          """/B.java
            |public class B {
            |  public void callA() {
            |    A.method();
            |  }
            |}
          """.stripMargin

        val `O.java` =
          """/O.java
            |
            |public class O {
            |  public void callA() {
            |    A.dummy();
            |  }
            |}
            |
            |""".stripMargin

        val `A2.java` =
          """/A.java
            |public class A {
            |  public static void methodRenamed() {}
            |  public static void dummy() {}
            |}
          """.stripMargin

        val `B2.java` =
          """/B.java
            |public class B {
            |  public void callA() {
            |    A.methodRenamed();
            |  }
            |}
          """.stripMargin

        val `C.scala` =
          """/C.scala
            |object C {
            |  val b = new B()
            |  b.callA()
            |}
          """.stripMargin

        val `Dummy1.scala` =
          """/Dummy1.scala
            |class Dummy1 {}
          """.stripMargin

        val `Dummy2.scala` =
          """/Dummy2.scala
            |class Dummy2 {}
          """.stripMargin

      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(
          Sources.`A1.java`,
          Sources.`B1.java`,
          Sources.`O.java`,
          Sources.`C.scala`,
          Sources.`Dummy1.scala`,
          Sources.`Dummy2.scala`
        )
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
      writeFile(`A`.srcFor("A.java"), Sources.`A2.java`)
      writeFile(`A`.srcFor("B.java"), Sources.`B2.java`)

      val newState = compiledState.compile(`A`)
      assert(newState.status == ExitStatus.Ok)
      assertValidCompilationState(newState, projects)
    }
  }
}
