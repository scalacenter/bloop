package bloop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import bloop.cli.CommonOptions
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.engine.ExecutionContext
import bloop.engine.Feedback
import bloop.engine.NoPool
import bloop.io.AbsolutePath
import bloop.io.Environment.LineSplitter
import bloop.io.Environment.lineSeparator
import bloop.io.RelativePath
import bloop.io.{Paths => BloopPaths}
import bloop.logging.RecordingLogger
import bloop.task.Task
import bloop.testing.DiffAssertions
import bloop.util.BaseTestProject
import bloop.util.BuildUtil
import bloop.util.TestUtil

abstract class BaseCompileSpec extends bloop.testing.BaseSuite {
  protected def TestProject: BaseTestProject

  protected def extraCompilationMessageOutput: String = ""
  protected def processOutput(output: String) = output

  def checkCompiles(
      name: String,
      expectedStatus: ExitStatus,
      scalaVersion: String,
      code: String,
      expectedException: Option[String] = None
  ) = {

    test(name) {
      TestUtil.withinWorkspace { workspace =>
        val sources = List(
          s"""/main/scala/Foo.scala
             |$code
          """.stripMargin
        )

        val logger = new RecordingLogger(ansiCodesSupported = false)
        val `A` = TestProject(workspace, "a", sources, scalaVersion = Some(scalaVersion))
        val projects = List(`A`)
        val state = loadState(workspace, projects, logger)
        try {
          val compiledState = state.compile(`A`)
          assertExitStatus(compiledState, expectedStatus)
          if (expectedStatus == ExitStatus.Ok)
            assertValidCompilationState(compiledState, projects)
        } catch {
          case NonFatal(e) =>
            expectedException match {
              case None => fail(s"Unexpected exception: ${e.getMessage()}")
              case Some(value) => assertNoDiff(e.toString, value)
            }
        }
      }
    }
  }

  // https://github.com/scala/scala3/issues/22026
  checkCompiles(
    "scala3-i22026",
    ExitStatus.UnexpectedError,
    "3.5.2",
    """|trait TypeBound {
       |
       |  type Min
       |  type Max >: Min
       |}
       |
       |object TypeBound {
       |
       |  type Pinpoint = TypeBound { type Max = Min }
       |}
       |
       |object HasPoly1 {
       |
       |  trait Poly1[B <: TypeBound] {
       |
       |    type Refined[Sub <: B]
       |
       |    def refine[Sub <: B](sub: Sub): Refined[Sub]
       |  }
       |
       |  object Poly1 {
       |
       |    type Concrete = Poly1[? <: TypeBound.Pinpoint] // can only refine using a concrete type, not a type bound
       |
       |    case class Example1() extends Poly1[TypeBound.Pinpoint] {
       |
       |      case class Refined[Sub <: TypeBound.Pinpoint](sub: Sub) {
       |
       |        final val fn: sub.Max => Seq[sub.Max] = { v =>
       |          Seq(v)
       |        }
       |      }
       |
       |      def refine[Sub <: TypeBound.Pinpoint](sub: Sub): Refined[Sub] = Refined[Sub](sub)
       |    }
       |  }
       |}""".stripMargin,
    expectedException = Some(
      "Encountered a error while persisting zinc analysis. Please report an issue in sbt/zinc repository."
    )
  )

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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(compiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(secondCompiledState, projects)
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
      logger.debugs.foreach { debug =>
        assert(!debug.contains("Classpath hash changed"))
      }
    }
  }
  test("compile-with-Vprint:typer") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources, scalacOptions = List("-Vprint:typer"))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      assertNoDiff(
        logger.infos.filterNot(_.contains("Compiled")).mkString("\n").trim(),
        """|Compiling a (1 Scala source)
           |[[syntax trees at end of                     typer]] // Foo.scala
           |package <empty> {
           |  class Foo extends scala.AnyRef {
           |    def <init>(): Foo = {
           |      Foo.super.<init>();
           |      ()
           |    }
           |  }
           |}
           |""".stripMargin
      )
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
      assertNoDiff(
        logger.compilingInfos.mkString(lineSeparator),
        s"""
           |Compiling a (1 Scala source)
        """.stripMargin
      )

      val analysisFile = compiledState.getProjectFor(`A`).analysisOut
      assert(analysisFile.exists)
      Files.delete(analysisFile.underlying)

      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assert(analysisFile.exists)

      // Logger should contain only the log from the previous compile, as this is a no-op
      assertNoDiff(
        logger.compilingInfos.mkString(lineSeparator),
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // This state loads the previous analysis from the persisted file
      val independentLogger = new RecordingLogger(ansiCodesSupported = false)
      val independentState = loadState(workspace, projects, independentLogger)
      assertSuccessfulCompilation(independentState, projects, isNoOp = false)

      // Assert that it's a no-op even if we sourced from the analysis
      val secondCompiledState = independentState.compile(`B`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertSuccessfulCompilation(secondCompiledState, projects, isNoOp = true)
      assertValidCompilationState(secondCompiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(compiledState, projects)
      assertExistingInternalClassesDir(secondCompiledState)(secondCompiledState, projects)
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
    }
  }

  test(s"compile scala 3 build incrementally") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A { def a = 1 }
          """.stripMargin

        val `A2.scala` =
          """/A.scala
            |class A { def a = 2 }
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`), scalaVersion = Some("3.1.1"))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A2.scala`))

      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertSuccessfulCompilation(secondCompiledState, projects, isNoOp = false)
      assertValidCompilationState(secondCompiledState, projects)
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
      assertNoDiff(
        logger.compilingInfos.mkString(lineSeparator),
        s"""
           |Compiling a (1 Scala source)
        """.stripMargin
      )

      val cleanState = compiledState.clean(`A`)
      val secondCompiledState = cleanState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assertDifferentExternalClassesDirs(compiledState, secondCompiledState, projects)
      assertNonExistingInternalClassesDir(secondCompiledState)(compiledState, projects)
      assertNoDiff(
        logger.compilingInfos.mkString(lineSeparator),
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

      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, List(`A`, `B`))
      assertNoDiff(
        logger.compilingInfos.mkString(lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling b (1 Scala source)""".stripMargin
      )

      assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A2.scala`))
      val secondCompiledState = compiledState.compile(`B`)
      assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
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
      assertExitStatus(thirdCompiledState, ExitStatus.Ok)
      assertValidCompilationState(thirdCompiledState, List(`A`, `B`))
      assertSameExternalClassesDirs(compiledState, thirdCompiledState, projects)

      /*
       * Scenario: class files get removed from external classes dir.
       * Expected: compilation succeeds (no-op) and external classes dir is repopulated.
       */

      BloopPaths.delete(AbsolutePath(`B`.config.classesDir))
      val fourthCompiledState = thirdCompiledState.compile(`B`)
      assertExitStatus(fourthCompiledState, ExitStatus.Ok)
      assertValidCompilationState(fourthCompiledState, List(`A`, `B`))
      assertSameExternalClassesDirs(compiledState, fourthCompiledState, projects)

      /*
       * Scenario: one class file is modified in the external classes dir and a source file changes.
       * Expected: incremental compilation succeeds and external classes dir is repopulated.
       */

      writeFile(`A`.externalClassFileFor("Bar.class"), "incorrect class file contents")
      assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A3.scala`))
      val fifthCompiledState = fourthCompiledState.compile(`B`)
      assertExitStatus(fifthCompiledState, ExitStatus.Ok)
      assertValidCompilationState(fifthCompiledState, List(`A`, `B`))
      assertIsFile(`A`.externalClassFileFor("Bar.class"))

      /*
       * Scenario: a classes directory of the last successful result is removed.
       * Expected: we use an empty result instead.
       */

      BloopPaths.delete(fifthCompiledState.getLastClassesDir(`A`).get)
      BloopPaths.delete(fifthCompiledState.getLastClassesDir(`B`).get)
      val sixthCompiledState = fifthCompiledState.compile(`B`)
      assertExitStatus(sixthCompiledState, ExitStatus.Ok)
      assertValidCompilationState(sixthCompiledState, List(`A`, `B`))

      assertNoDiff(
        logger.compilingInfos.mkString(lineSeparator),
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
      assertExitStatus(compiledState, ExitStatus.Ok)

      // Only the build graph of `E` is compiled successfully
      assertValidCompilationState(compiledState, List(`A`, `B`, `C`, `D`, `E`))
      assertEmptyCompilationState(compiledState, List(`F`))
      assertNoDiff(
        logger.compilingInfos.sorted.mkString(lineSeparator),
        """Compiling a (1 Scala source)
          |Compiling b (1 Scala source)
          |Compiling c (1 Scala source)
          |Compiling d (1 Scala source)
          |Compiling e (1 Scala source)""".stripMargin
      )
      assertNoDiff(
        logger.warnings.sorted.mkString(lineSeparator),
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }

  test("compile all on empty projects") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |package a
            |object A {
            |  val HelloWorld: String = "Hello World!"
            |}""".stripMargin
        val `B.scala` =
          """/B.scala
            |package b
            |object B {
            |  println(a.A.HelloWorld)
            |}""".stripMargin
        val `C.scala` =
          """/C.scala
            |package b
            |object C {
            |  println(a.A.HelloWorld)
            |  println(b.B)
            |}""".stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`, Sources.`C.scala`), List(`A`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile()
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      assertNoDiff(
        logger.compilingInfos.sorted.mkString(lineSeparator),
        """|Compiling a (1 Scala source)
           |Compiling b (2 Scala sources)
          """.stripMargin
      )
    }
  }

  test("compile after moving a class across project + invalidating symbol in a dependent project") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/Foo.scala
            |package common
            |import Enrichments._
            |class Foo
          """.stripMargin

        // A dummy file to avoid surpassing the 50% changed sources and trigger a full compile
        val `Dummy.scala` =
          """/Dummy.scala
            |package common
            |class Dummy
          """.stripMargin

        // A second dummy file
        val `Dummy2.scala` =
          """/Dummy2.scala
            |package common
            |class Dummy2
          """.stripMargin

        val `Enrichments.scala` =
          """/Enrichments.scala
            |package common
            |object Enrichments {}
          """.stripMargin

        val `Enrichments2.scala` =
          """/Enrichments.scala
            |package common
            |object Enrichments {
            |  implicit class XtensionString(str: String) {
            |    def isGreeting: Boolean = str == "Hello world"
            |  }
            |}
          """.stripMargin

        val `Bar.scala` =
          """/Bar.scala
            |package common
            |import Enrichments._
            |class Bar {
            |  val foo: Foo = new Foo
            |  def hello: String = "hello"
            |  println(hello)
            |}
          """.stripMargin

        val `Baz.scala` =
          """/Baz.scala
            |package common
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // Move `Bar.scala` from `B` to `A`; code still compiles
      Files.move(
        `B`.srcFor("Bar.scala").underlying,
        `A`.srcFor("Bar.scala", exists = false).underlying
      )

      val secondCompiledState = compiledState.compile(`B`)
      assertNoDiff(logger.errors.mkString(lineSeparator), "")
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)
      assertDifferentExternalClassesDirs(secondCompiledState, compiledStateBackup, projects)

      writeFile(`A`.srcFor("Enrichments.scala"), Sources.`Enrichments2.scala`)
      val thirdCompiledState = secondCompiledState.compile(`B`)
      assertNoDiff(logger.errors.mkString(lineSeparator), "")
      assertExitStatus(thirdCompiledState, ExitStatus.Ok)
      assertValidCompilationState(thirdCompiledState, projects)
      assertDifferentExternalClassesDirs(thirdCompiledState, secondCompiledState, projects)
    }
  }

  test("don't compile after renaming a Scala class and not its references in the same project") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/Foo.scala
            |class Foo
          """.stripMargin

        val `Bar.scala` =
          """/Bar.scala
            |class Bar {
            |  val foo: Foo = new Foo
            |}
          """.stripMargin

        val `Foo2.scala` =
          """/Foo.scala
            |class Foo2
          """.stripMargin

        val `Bar2.scala` =
          """/Bar.scala
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
      assertIsFile(writeFile(`A`.srcFor("Foo.scala"), Sources.`Foo2.scala`))
      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
      assertInvalidCompilationState(
        secondCompiledState,
        List(`A`),
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )

      val targetBar = TestUtil.universalPath("a/src/Bar.scala")
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

      assertIsFile(writeFile(`A`.srcFor("Bar.scala"), Sources.`Bar2.scala`))
      val thirdCompiledState = secondCompiledState.compile(`A`)
      assertExitStatus(thirdCompiledState, ExitStatus.Ok)

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
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        // #2: Compiler after changing public API of `Foo`, which should make `Bar` fail
        assertIsFile(writeFile(`A`.srcFor("Foo.java"), Sources.`Foo2.java`))
        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
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
        assertExitStatus(thirdCompiledState, ExitStatus.Ok)
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      assertIsFile(writeFile(`A`.srcFor("Bar.scala"), Sources.`Bar2.scala`))
      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
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
          s"""|[E2] ${targetFoo}:3:19
              |     cannot find symbol
              |       symbol:   class Bar
              |       location: class Foo
              |     L3: Bar
              |                           ^^^
              |[E1] ${targetFoo}:3:5
              |     cannot find symbol
              |       symbol:   class Bar
              |       location: class Foo
              |     L3: Bar
              |             ^^^
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
      assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
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
      assertExitStatus(thirdCompiledState, ExitStatus.Ok)
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
          """/Dummy.scala
            |class Dummy
          """.stripMargin

        val `Foo.scala` =
          """/Foo.scala
            |class Foo {
            |  def foo: String = ""
            |}
          """.stripMargin

        val `Bar.scala` =
          """/Bar.scala
            |class Bar extends Foo
          """.stripMargin

        val `Baz.scala` =
          """/Baz.scala
            |class Baz {
            |  val bar: Bar = new Bar
            |  def hello = println(bar.foo)
            |}
          """.stripMargin

        val `Dummy1.scala` =
          """/Dummy1.scala
            |class Dummy1
          """.stripMargin

        val `Dummy2.scala` =
          """/Dummy2.scala
            |class Dummy2
          """.stripMargin

        val `Foo2.scala` =
          """/Foo.scala
            |class Foo {
            |  def foo: String = ""
            |  def foo2: String = ""
            |}
          """.stripMargin

        val `Baz2.scala` =
          """/Baz.scala
            |class Baz {
            |  val bar: Bar = new Bar
            |  def hello = println(bar.foo2)
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
          Sources.`Baz.scala`,
          Sources.`Dummy1.scala`,
          Sources.`Dummy2.scala`
        ),
        List(`A`)
      )

      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`B`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      Files.move(
        `B`.srcFor("Foo.scala").underlying,
        `A`.srcFor("Foo.scala", exists = false).underlying
      )

      writeFile(`A`.srcFor("Foo.scala"), Sources.`Foo2.scala`)

      val compiledStateBackup = compiledState.backup

      // Compile first only `A`
      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, List(`A`))
      assertDifferentExternalClassesDirs(secondCompiledState, compiledStateBackup, `A`)
      assertExistingCompileProduct(secondCompiledState, `A`, RelativePath("Foo.class"))

      // Add change depending on new method to make sure that `Foo.class` coming from dependency is picked
      writeFile(`B`.srcFor("Baz.scala"), Sources.`Baz2.scala`)

      // Then compile `B` to make sure right info from `A` is passed to `B` for invalidation
      val thirdCompiledState = secondCompiledState.compile(`B`)
      assertExitStatus(thirdCompiledState, ExitStatus.Ok)
      assertValidCompilationState(thirdCompiledState, List(`A`, `B`))
      assertNonExistingCompileProduct(thirdCompiledState, `B`, RelativePath("Foo.class"))
      assertExistingCompileProduct(thirdCompiledState, `B`, RelativePath("Bar.class"))
      assertExistingCompileProduct(thirdCompiledState, `B`, RelativePath("Baz.class"))
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
      assertExitStatus(compiledState, ExitStatus.CompilationError)
      assertInvalidCompilationState(
        compiledState,
        projects,
        existsAnalysisFile = false,
        hasPreviousSuccessful = false,
        hasSameContentsInClassesDir = true
      )

      val targetB = TestUtil.universalPath("a/src/B.java")
      val cannotFindSymbolError: String = {
        s"""[E1] ${targetB}:1:24
           |     cannot find symbol
           |       symbol: class A
           |     L1: A
           |                                ^
           |${targetB}: L1 [E1]""".stripMargin
      }

      assertDiagnosticsResult(compiledState.getLastResultFor(`A`), 1)
      assertNoDiff(
        logger.renderErrors(exceptContaining = "Failed to compile"),
        cannotFindSymbolError
      )
    }
  }

  for (fatalOpt <- List("-Xfatal-warnings", "-Werror"))
    test(s"support $fatalOpt internal implementation") {
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
        val options = List("-Ywarn-unused", fatalOpt)
        val `A` = TestProject(workspace, "a", sources, scalacOptions = options)
        val projects = List(`A`)
        val state = loadState(workspace, projects, logger)
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.CompilationError)
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

  test("scalac -Xfatal-warnings should not set java fatal warnings as errors") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Main.scala` =
          """/Main.scala
            |object Main extends App {
            |  println("Hello, World!")
            |}
            """.stripMargin
        val `Service.java` =
          """/Service.java
            |package com.example;
            |import java.util.List;
            |public class Service {
            |    String aaa = 123;
            |    public void create(List<Object> args) {
            |        var second = (java.util.Optional<String>) args.get(2);
            |        System.out.println(second);
            |    }
            |}
            """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val sources = List(Sources.`Main.scala`, Sources.`Service.java`)
      val scalacOptions = List("-Xfatal-warnings")
      val javacOptions = List("-Xlint:unchecked")
      val `A` = TestProject(
        workspace,
        "a",
        sources,
        scalacOptions = scalacOptions,
        javacOptions = javacOptions
      )
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.CompilationError)

      val targetService = TestUtil.universalPath("a/src/Service.java")
      assertNoDiff(
        logger.renderErrors(exceptContaining = "Failed to compile"),
        s"""[E1] ${targetService}:4:18
           |     incompatible types: int cannot be converted to java.lang.String
           |     L4: 123
           |                          ^^^
           |${TestUtil.universalPath("a/src/Service.java")}: L4 [E1], L6 [E2]
           |""".stripMargin
      )
      assertDiagnosticsResult(
        compiledState.getLastResultFor(`A`),
        errors = 1,
        warnings = 1,
        expectFatalWarnings = false
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
      assertExitStatus(compiledState, ExitStatus.CompilationError)
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

      assertExitStatus(compiledState, ExitStatus.CompilationError)
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

      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
      assertNoDiff(
        logger.compilingInfos.sorted.mkString(lineSeparator),
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
      assertExitStatus(compiledMacrosState, ExitStatus.Ok)
      assertValidCompilationState(compiledMacrosState, List(build.macroProject))

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
        try Await.result(backgroundCompiledUserState, Duration(8000, "ms"))
        catch {
          case NonFatal(t) => backgroundCompiledUserState.cancel(); throw t
          case _: InterruptedException => backgroundCompiledUserState.cancel(); compiledMacrosState
        }
      }

      assertExitStatus(compiledUserState, ExitStatus.CompilationError)
      assertCancelledCompilation(compiledUserState, List(build.userProject))
      assertNoDiff(
        logger.warnings.mkString(lineSeparator),
        "Cancelling compilation of user"
      )
    }
  }

  test("resources only downstream project resolved correctly") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val build = loadBuildFromResources("resources-only-downstream-project", workspace, logger)
      val projectB = build.projectFor("projectB")
      val compiledState = build.state.compile(projectB)
      val runState = compiledState.run(projectB)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertExitStatus(runState, ExitStatus.Ok)
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
            |    println("Allowed application was compiled successfully v2.0")
            |  }
            |}
        """.stripMargin

        val `App3.scala` =
          """package hello
            |
            |object App {
            |  def main(args: Array[String]): Unit = {
            |    println("Allowed application was compiled successfully v3.0")
            |  }
            |}
        """.stripMargin
      }

      // This test is more manual than usual because we use a build defined in resources
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val build = loadBuildFromResources("compiler-plugin-allowlist", workspace, logger)

      val allowlistProject = build.projectFor("allowlistJS")
      val compiledState = build.state.compile(allowlistProject)

      val previousCacheHits = logger.debugs.count(_.startsWith("Cache hit true")).toLong
      val targetMsg = "Bloop test plugin classloader: scala.reflect.internal.util.ScalaClassLoader"

      logger.infos.find(_.contains(targetMsg)) match {
        case Some(found) =>
          val `App.scala` = allowlistProject.srcFor("hello/App.scala")
          assertIsFile(writeFile(`App.scala`, Sources.`App2.scala`))

          compiledState.compile(allowlistProject)

          // The recompilation forces the compiler to show the hashcode of plugin classloader
          val foundMessages = logger.infos.count(_ == found)
          assert(foundMessages == 2)

          // Ensure that the next time we compile we hit the cache that tells us to allow or not
          val totalCacheHits = logger.debugs.count(_.startsWith("Cache hit true")).toLong
          assert((totalCacheHits - previousCacheHits) == 16)

          // Disable the cache manually by changing scalac options in configuration file
          val (newAllowlistProject, stateWithDisabledPluginClassloader) = {
            val remainingProjects = build.projects.filter(_ != allowlistProject)
            val currentOptions = allowlistProject.config.scala.map(_.options).getOrElse(Nil)
            val scalacOptions = "-Ycache-plugin-class-loader:none" :: currentOptions
            val newScala = allowlistProject.config.scala.map(_.copy(options = scalacOptions))
            val newAllowlistProject =
              new util.TestProject(allowlistProject.config.copy(scala = newScala), None)
            val newProjects = newAllowlistProject :: remainingProjects
            populateWorkspace(build, List(newAllowlistProject))
            newAllowlistProject -> loadState(workspace, newProjects, logger)
          }

          // Force 3rd and last incremental compiler iteration to check the hash changes
          assertIsFile(writeFile(`App.scala`, Sources.`App3.scala`))
          stateWithDisabledPluginClassloader.compile(newAllowlistProject)
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
      assertExitStatus(compiledState, ExitStatus.CompilationError)
      assertNoDiff(
        logger.errors.mkString(lineSeparator),
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
      assertExitStatus(compiledState, ExitStatus.CompilationError)
      assert(logger.errors.size == 4)

      val targetA = TestUtil.universalPath("a/src/A.scala")
      assertNoDiff(
        logger.errors.mkString(lineSeparator),
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
      def runCompileAsync = Task.eval(Cli.run(compileAction, NoPool)).executeAsync
      val runCompile = Task.gatherUnordered(List(runCompileAsync, runCompileAsync)).map(_ => ())
      Await.result(runCompile.runAsync(ExecutionContext.ioScheduler), FiniteDuration(10, "s"))

      val actionsOutput = new String(testOut.toByteArray(), StandardCharsets.UTF_8)
      def removeAsciiColorCodes(line: String): String = line.replaceAll("\u001B\\[[;\\d]*m", "")

      val obtained = actionsOutput.splitLines
        .filterNot(_.startsWith("Compiled"))
        .map(removeAsciiColorCodes)
        .map(msg => RecordingLogger.replaceTimingInfo(msg))
        .mkString(lineSeparator)
        .replaceAll("'(bloop-cli-.*)'", "'bloop-cli'")
        .replaceAll("'bloop-cli'", "???")

      try {
        assertNoDiff(
          processOutput(obtained),
          s"""Compiling a (1 Scala source)
             |Deduplicating compilation of a from cli client ??? (since ???
             |Compiling a (1 Scala source)
             |$extraCompilationMessageOutput
             |""".stripMargin
        )
      } catch {
        case _: DiffAssertions.TestFailedException =>
          assertNoDiff(
            processOutput(obtained),
            s"""
               |Deduplicating compilation of a from cli client ??? (since ???
               |Compiling a (1 Scala source)
               |Compiling a (1 Scala source)
               |$extraCompilationMessageOutput
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }

  test("recompile entire mixed project") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Dummy1.scala` =
          """/foo/Dummy1.scala
            |class Dummy1""".stripMargin
        val `Dummy2.scala` =
          """/foo/Dummy2.scala
            |class Dummy2""".stripMargin
        val `Dummy3.scala` =
          """/foo/Dummy3.scala
            |class Dummy3""".stripMargin
        val `Dummy4.scala` =
          """/foo/Dummy4.scala
            |class Dummy4""".stripMargin
        val `Dummy1-changed.scala` =
          """/foo/Dummy1.scala
            |class Dummy1 // changed""".stripMargin
        val `Dummy2-changed.scala` =
          """/foo/Dummy2.scala
            |class Dummy2 // changed""".stripMargin
        val `Dummy3-changed.scala` =
          """/foo/Dummy3.scala
            |class Dummy3 // changed""".stripMargin
        val `Dummy4-changed.scala` =
          """/foo/Dummy4.scala
            |class Dummy4 // changed""".stripMargin
        val `A.java` =
          """/foo/A.java
            |package foo;
            |public class A {
            |  public B b;
            |}""".stripMargin
        val `B.scala` =
          """/foo/B.scala
            |package foo
            |class B {
            |  var a: A = _
            |}""".stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(
          Sources.`A.java`,
          Sources.`B.scala`,
          Sources.`Dummy1.scala`,
          Sources.`Dummy2.scala`,
          Sources.`Dummy3.scala`,
          Sources.`Dummy4.scala`
        )
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // Change enough sources to force recompiling the whole project.
      writeFile(`A`.srcFor("/foo/Dummy1.scala"), Sources.`Dummy1-changed.scala`)
      writeFile(`A`.srcFor("/foo/Dummy2.scala"), Sources.`Dummy2-changed.scala`)
      writeFile(`A`.srcFor("/foo/Dummy3.scala"), Sources.`Dummy3-changed.scala`)
      writeFile(`A`.srcFor("/foo/Dummy4.scala"), Sources.`Dummy4-changed.scala`)

      val newState = compiledState.compile(`A`)
      assertExitStatus(newState, ExitStatus.Ok)
      assertValidCompilationState(newState, projects)
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
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
      writeFile(`A`.srcFor("A.java"), Sources.`A2.java`)
      writeFile(`A`.srcFor("B.java"), Sources.`B2.java`)

      val newState = compiledState.compile(`A`)
      assertExitStatus(newState, ExitStatus.Ok)
      assertValidCompilationState(newState, projects)
    }
  }

  test("compile uses the compilation classpath") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `a/pkg1/A.scala` =
          """/a/pkg1/A.scala
            |package pkg1
            |object A""".stripMargin
        val `b/pkg2/B.scala` =
          """/b/pkg2/B.scala
            |package pkg2
            |object B""".stripMargin
        val `c/C.scala` =
          """/c/C.scala
            |object C {
            |  val a = pkg1.A
            |  val b = pkg2.B
            |}""".stripMargin

      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`a/pkg1/A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`b/pkg2/B.scala`), List(`A`))
      val `C` =
        TestProject(workspace, "c", List(Sources.`c/C.scala`), List(`B`), strictDependencies = true)
      val `D` = TestProject(workspace, "d", List(Sources.`c/C.scala`), List(`B`))
      val projects = List(`A`, `B`, `C`, `D`)
      val state = loadState(workspace, projects, logger)

      // Compiling `C` fails because only `B` is on the classpath during compilation
      val failedState = state.compile(`C`)
      assertExitStatus(failedState, ExitStatus.CompilationError)

      // Compiling `D` succeeds because both `A` and `B` are on the classpath.
      val compiledState = state.compile(`D`)
      assertExitStatus(compiledState, ExitStatus.Ok)
    }
  }

  test("compile sees compile-time resources") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `a/Macro.scala` =
          """/a/Macro.scala
            |package a
            |import scala.io.Source
            |import scala.language.experimental.macros
            |import scala.reflect.macros.blackbox.Context
            |object M {
            |  def m(ctx: Context)(): ctx.Tree = {
            |    val res = getClass.getClassLoader.getResourceAsStream("resource.txt")
            |    val content = Source.fromInputStream(res).mkString
            |    assert(content == "hello")
            |    import ctx.universe._
            |    q"()"
            |  }
            |  def check(): Unit = macro m
            |}""".stripMargin
        val `b/B.scala` =
          """/b/B.scala
            |package b
            |object B {
            |  a.M.check()
            |}""".stripMargin
      }
      object Resources {
        val `b/compile-resources/resource.txt` =
          """/resource.txt
            |hello""".stripMargin
        val `b/run-resources/resource.txt` =
          """/resource.txt
            |goodbye""".stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`a/Macro.scala`))
      val `B` = TestProject(
        workspace,
        "b",
        List(Sources.`b/B.scala`),
        List(`A`),
        resources = List(Resources.`b/compile-resources/resource.txt`),
        runtimeResources = Some(List(Resources.`b/run-resources/resource.txt`))
      )

      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)

      val compiledState = state.compile(`B`)
      assertExitStatus(compiledState, ExitStatus.Ok)
    }
  }

  test("detects removed products") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/a/A.scala
            |package a
            |class A
          """.stripMargin

        val `B.scala` =
          """/a/B.scala
            |package a
            |class B extends A
          """.stripMargin

        val `B2.scala` =
          """/a/B.scala
            |package a
            |class B extends A {
            |  def foo: Int = ???
            |}
          """.stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      val firstState = state.compile(`A`)
      assertExitStatus(firstState, ExitStatus.Ok)

      writeFile(`A`.srcFor("a/B.scala"), Sources.`B2.scala`)
      def deleteAProduct(classesDir: AbsolutePath): Unit = {
        val productA = classesDir.resolve("a").resolve("A.class")
        Files.delete(productA.underlying)
      }

      deleteAProduct(firstState.getLastClassesDir(`A`).get)

      val secondState = firstState.compile(`A`)
      assertExitStatus(secondState, ExitStatus.Ok)

    }
  }

  test("fail compilation when using wildcard import from empty package - after package rename") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Sun.scala` =
          """/main/scala/Z/A/Sun.scala
            |package org.someorg.Z.A
            |class Sun
          """.stripMargin
        val `Mercury.scala` =
          """/main/scala/Z/A/Mercury.scala
            |package org.someorg.Z.A
            |class Mercury
          """.stripMargin
        val `Moon.scala` =
          """/main/scala/B/Moon.scala
            |package org.someorg.B
            |
            |import org.someorg.Z.A._
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
        val `Sun2.scala` =
          """/main/scala/Z/A/Sun.scala
            |package org.someorg.Z.C
            |class Sun
          """.stripMargin
        val `Mercury2.scala` =
          """/main/scala/Z/A/Mercury.scala
            |package org.someorg.Z.C
            |class Mercury
          """.stripMargin
        val `Moon2.scala` =
          """/main/scala/B/Moon.scala
            |package org.someorg.B
            |
            |import org.someorg.Z.A._
            |import org.someorg.Z.C.{Sun, Mercury}
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
      }
      val logger = new RecordingLogger(
        debug = true,
        ansiCodesSupported = false,
        debugOut = Some(new PrintStream(System.out))
      )

      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`Sun.scala`, Sources.`Mercury.scala`, Sources.`Moon.scala`)
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      val firstState = state.compile(`A`)
      assertExitStatus(firstState, ExitStatus.Ok)

      writeFile(`A`.srcFor("/main/scala/Z/A/Sun.scala"), Sources.`Sun2.scala`)
      writeFile(`A`.srcFor("/main/scala/Z/A/Mercury.scala"), Sources.`Mercury2.scala`)
      writeFile(`A`.srcFor("/main/scala/B/Moon.scala"), Sources.`Moon2.scala`)

      val secondState = firstState.compile(`A`)
      assertExitStatus(secondState, ExitStatus.CompilationError)
      assertInvalidCompilationState(
        secondState,
        projects,
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )
      assertExistingInternalClassesDir(secondState)(firstState, projects)
    }
  }

  test(
    "fail compilation when using wildcard import from empty package if sub-packages are empty too"
  ) {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Sun.scala` =
          """/main/scala/Z/A/B/Sun.scala
            |package org.someorg.Z.A.B
            |class Sun
          """.stripMargin
        val `Mercury.scala` =
          """/main/scala/Z/A/Mercury.scala
            |package org.someorg.Z.A
            |class Mercury
          """.stripMargin
        val `Moon.scala` =
          """/main/scala/Y/Moon.scala
            |package org.someorg.Y
            |
            |import org.someorg.Z.A.B._
            |import org.someorg.Z.A._
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
        val `Sun2.scala` =
          """/main/scala/Z/A/B/Sun.scala
            |package org.someorg.Z.D.C
            |class Sun
          """.stripMargin
        val `Mercury2.scala` =
          """/main/scala/Z/A/Mercury.scala
            |package org.someorg.Z.D
            |class Mercury
          """.stripMargin
        val `Moon2.scala` =
          """/main/scala/Y/Moon.scala
            |package org.someorg.Y
            |
            |import org.someorg.Z.A.B._
            |import org.someorg.Z.A._
            |import org.someorg.Z.D.C.Sun
            |import org.someorg.Z.D.Mercury
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
      }
      val logger = new RecordingLogger(
        debug = true,
        ansiCodesSupported = false,
        debugOut = Some(new PrintStream(System.out))
      )

      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`Sun.scala`, Sources.`Mercury.scala`, Sources.`Moon.scala`)
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      val firstState = state.compile(`A`)
      assertExitStatus(firstState, ExitStatus.Ok)

      writeFile(`A`.srcFor("/main/scala/Z/A/B/Sun.scala"), Sources.`Sun2.scala`)
      writeFile(`A`.srcFor("/main/scala/Z/A/Mercury.scala"), Sources.`Mercury2.scala`)
      writeFile(`A`.srcFor("/main/scala/Y/Moon.scala"), Sources.`Moon2.scala`)

      val secondState = firstState.compile(`A`)
      assertExitStatus(secondState, ExitStatus.CompilationError)
      assertInvalidCompilationState(
        secondState,
        projects,
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )
      assertExistingInternalClassesDir(secondState)(firstState, projects)
    }
  }

  test(
    "compilation succeed if using wildcard import from package where dependency was replaced with different one"
  ) {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Sun.scala` =
          """/main/scala/Z/A/B/Sun.scala
            |package org.someorg.Z.A.B
            |class Sun
          """.stripMargin
        val `Mercury.scala` =
          """/main/scala/Z/A/Mercury.scala
            |package org.someorg.Z.A
            |class Mercury
          """.stripMargin
        val `Moon.scala` =
          """/main/scala/Y/Moon.scala
            |package org.someorg.Y
            |
            |import org.someorg.Z.A._
            |import org.someorg.Z.A.B.Sun
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
        val `Sun2.scala` =
          """/main/scala/Z/A/B/Sun.scala
            |package org.someorg.Z.A
            |class Sun
          """.stripMargin
        val `Mercury2.scala` =
          """/main/scala/Z/A/Mercury.scala
            |package org.someorg.Z.D
            |class Mercury
          """.stripMargin
        val `Moon2.scala` =
          """/main/scala/Y/Moon.scala
            |package org.someorg.Y
            |
            |import org.someorg.Z.A._
            |import org.someorg.Z.D.Mercury
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
      }
      val logger = new RecordingLogger(
        debug = true,
        ansiCodesSupported = false,
        debugOut = Some(new PrintStream(System.out))
      )

      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`Sun.scala`, Sources.`Mercury.scala`, Sources.`Moon.scala`)
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      val firstState = state.compile(`A`)
      assertExitStatus(firstState, ExitStatus.Ok)

      writeFile(`A`.srcFor("/main/scala/Z/A/B/Sun.scala"), Sources.`Sun2.scala`)
      writeFile(`A`.srcFor("/main/scala/Z/A/Mercury.scala"), Sources.`Mercury2.scala`)
      writeFile(`A`.srcFor("/main/scala/Y/Moon.scala"), Sources.`Moon2.scala`)

      val secondState = firstState.compile(`A`)
      assertExitStatus(secondState, ExitStatus.Ok)
      assertInvalidCompilationState(
        secondState,
        projects,
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )
      assertNonExistingInternalClassesDir(secondState)(firstState, projects)
    }
  }

  test(
    "pruning packages - whole package path is deleted if it is empty"
  ) {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Sun.scala` =
          """/main/scala/A/B/C/D/Sun.scala
            |package org.someorg.A.B.C.D
            |class Sun
          """.stripMargin
        val `Mercury.scala` =
          """/main/scala/A/Mercury.scala
            |package org.someorg.A
            |class Mercury
          """.stripMargin
        val `Moon.scala` =
          """/main/scala/Y/Moon.scala
            |package org.someorg.Y
            |
            |import org.someorg.A._
            |import org.someorg.A.B.C.D.Sun
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
        val `Sun2.scala` =
          """/main/scala/A/B/C/D/Sun.scala
            |package org.someorg.Z.B.C.D
            |class Sun
          """.stripMargin
        val `Mercury2.scala` =
          """/main/scala/A/Mercury.scala
            |package org.someorg.Z
            |class Mercury
          """.stripMargin
        val `Moon2.scala` =
          """/main/scala/Y/Moon.scala
            |package org.someorg.Y
            |
            |import org.someorg.A._
            |import org.someorg.Z.B.C.D.Sun
            |import org.someorg.Z.Mercury
            |object Moon {
            |      val o = new Sun()
            |      val o1 = new Mercury()
            | }
          """.stripMargin
      }
      val logger = new RecordingLogger(
        debug = true,
        ansiCodesSupported = false,
        debugOut = Some(new PrintStream(System.out))
      )

      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`Sun.scala`, Sources.`Mercury.scala`, Sources.`Moon.scala`)
      )

      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      val firstState = state.compile(`A`)
      assertExitStatus(firstState, ExitStatus.Ok)

      writeFile(`A`.srcFor("/main/scala/A/B/C/D/Sun.scala"), Sources.`Sun2.scala`)
      writeFile(`A`.srcFor("/main/scala/A/Mercury.scala"), Sources.`Mercury2.scala`)
      writeFile(`A`.srcFor("/main/scala/Y/Moon.scala"), Sources.`Moon2.scala`)

      val secondState = firstState.compile(`A`)
      assertExitStatus(secondState, ExitStatus.CompilationError)
      assertInvalidCompilationState(
        secondState,
        projects,
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )
      assertExistingInternalClassesDir(secondState)(firstState, projects)
    }
  }

  test("unsafe") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |import sun.misc.Unsafe
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources, scalacOptions = List("-release", "8"))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }
}
