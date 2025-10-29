package bloop.bsp

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.internal.build.BuildInfo
import bloop.internal.build.BuildTestInfo
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.io.{Paths => BloopPaths}
import bloop.logging.RecordingLogger
import bloop.task.Task
import bloop.util.TestProject
import bloop.util.TestUtil

import coursierapi.Fetch

object TcpBspCompileSpec extends BspCompileSpec(BspProtocol.Tcp)
object LocalBspCompileSpec extends BspCompileSpec(BspProtocol.Local)

class BspCompileSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  test("initialize and exit a build via BSP") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "a", Nil)
      loadBspState(workspace, List(`A`), logger) { state =>
        assertExitStatus(state, ExitStatus.Ok)
      }
    }
    val contentLogs = logger.debugs
      .flatMap(_.split("\n"))
      .filter(msg => msg.startsWith("  --> content:") && !msg.contains("logMessage"))
    val allButInitializeRequest = contentLogs.filterNot(_.contains("""build/initialize""""))
    // Filter out the initialize request that contains platform-specific details
    assertNoDiff(
      allButInitializeRequest.mkString(lineSeparator),
      s"""|  --> content: ${TestConstants.buildInitialize}
          |  --> content: {"method":"build/initialized","jsonrpc":"2.0"}
          |  --> content: {"method":"build/shutdown","id":3,"jsonrpc":"2.0"}
          |  --> content: {"result":{},"id":3,"jsonrpc":"2.0"}
          |  --> content: {"method":"build/exit","jsonrpc":"2.0"}""".stripMargin
    )
  }

  test("no-op compile simple build") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin,
          compiledState.lastDiagnostics(`A`)
        )

        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)
        assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
        assertNoDiff(
          secondCompiledState.lastDiagnostics(`A`),
          "" // no-op
        )
      }
    }
  }

  test("compile simple build, clean and then compile again") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin,
          compiledState.lastDiagnostics(`A`)
        )

        val cleanedState = compiledState.clean(`A`)

        val secondCompiledState = cleanedState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)
        assertDifferentExternalClassesDirs(compiledState, secondCompiledState, projects)
        assertNoDiff(
          """#2: task start 2
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#2: task finish 2
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin,
          compiledState.lastDiagnostics(`A`)
        )
      }
    }
  }

  test("compile simple build with client origin id") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/Foo.scala
          |import Predef.assert
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val scalacOptions = List("-Ywarn-unused:imports")
      val `A` = TestProject(workspace, "a", sources, scalacOptions = scalacOptions)
      val projects = List(`A`)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`, originId = Some("test-origin"))
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """|#1: task start 1
             |  -> Msg: Compiling a (1 Scala source)
             |  -> Data kind: compile-task
             |#1: a/src/Foo.scala
             |  -> List(Diagnostic(Range(Position(0,0),Position(0,7)),Some(Warning),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
             |  -> reset = true
             |  -> origin = test-origin
             |#1: task finish 1
             |  -> errors 0, warnings 1, noop false
             |  -> origin = test-origin
             |  -> Msg: Compiled 'a'
             |  -> Data kind: compile-report
             |""".stripMargin
        )
      }
    }
  }

  testNonWindows(
    "create orphan client classes directory and make sure loading a BSP session cleans it up"
  ) {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      val cliState = loadState(workspace, projects, logger)
      val compiledState = cliState.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // Add extra client classes directory
      val projectA = compiledState.getProjectFor(`A`)
      val bspClientsRootDir = projectA.clientClassesRootDirectory
      val orphanClientClassesDir = bspClientsRootDir.resolve(s"classes-test-123aAfd12i23")
      Files.createDirectories(orphanClientClassesDir.underlying)
      val fileTime = FileTime.from(Instant.now().minusSeconds(120))
      Files.setLastModifiedTime(orphanClientClassesDir.underlying, fileTime)

      val orphanInternalClassesDir = projectA.out.resolve(s"classes-test-123aAfd12i23")
      Files.createDirectories(orphanInternalClassesDir.underlying)
      Files.setLastModifiedTime(orphanInternalClassesDir.underlying, fileTime)

      loadBspState(workspace, projects, logger) { bspState =>
        // Ask for scala options to force client to create a client classes dir for `A`
        val _ = bspState.scalaOptions(`A`)
      }

      // Wait until the extra directory is finally deleted at the end of the bsp session
      TestUtil.await(
        FiniteDuration(10, TimeUnit.SECONDS),
        bloop.engine.ExecutionContext.ioScheduler
      ) {
        Task {
          var check: Boolean = true
          while (check) {
            // The task cleaning up client classes directories should have removed the extra dir
            check = orphanClientClassesDir.exists && orphanInternalClassesDir.exists
            Thread.sleep(100)
          }
        }.timeoutTo(
          FiniteDuration(5, TimeUnit.SECONDS),
          Task(sys.error(s"Expected deletion of $orphanClientClassesDir"))
        )
      }
    }
  }

  test("compile incrementally a build") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/main/scala/Foo.scala
            |class Foo {
            |  def foo(s: String): String = s
            |}
          """.stripMargin
        val `Foo2.scala` =
          """/main/scala/Foo.scala
            |class Foo {
            |  // This body will not typecheck
            |  def foo(s: Int): String = s
            |}
          """.stripMargin
        val `Foo3.scala` =
          """/main/scala/Foo.scala
            |class Foo {
            |  // Will cause error in `Bar.scala`
            |  def foo(s: Int): String = s.toString
            |}
          """.stripMargin
        val `Bar.scala` =
          """/main/scala/Bar.scala
            |object Bar {
            |  def main(args: Array[String]): Unit = {
            |    (new Foo()).foo("asdf")
            |  }
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`Bar.scala`), List(`A`))
      val projects = List(`A`, `B`)

      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`B`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        assertNoDiff(
          compiledState.lastDiagnostics(`B`),
          """#1: task start 2
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 2
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report""".stripMargin
        )

        writeFile(`A`.srcFor("/main/scala/Foo.scala"), Sources.`Foo2.scala`)

        val secondCompiledState = compiledState.compile(`B`)
        assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
        assertValidCompilationState(secondCompiledState, List(`B`))
        assertInvalidCompilationState(
          secondCompiledState,
          List(`A`),
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )

        assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
        assertNoDiff(compiledState.lastDiagnostics(`B`), "")
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """
            |#2: task start 3
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#2: a/src/main/scala/Foo.scala
            |  -> List(Diagnostic(Range(Position(2,28),Position(2,28)),Some(Error),Some(_),Some(_),type mismatch;  found   : Int  required: String,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#2: task finish 3
            |  -> errors 1, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report """.stripMargin
        )

        writeFile(`A`.srcFor("/main/scala/Foo.scala"), Sources.`Foo3.scala`)

        val thirdCompiledState = secondCompiledState.compile(`B`)
        assertExitStatus(thirdCompiledState, ExitStatus.CompilationError)
        assertValidCompilationState(thirdCompiledState, List(`A`))
        assertInvalidCompilationState(
          thirdCompiledState,
          List(`B`),
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )

        assertSameExternalClassesDirs(compiledState, thirdCompiledState, `B`)
        assertDifferentExternalClassesDirs(compiledState, thirdCompiledState, `A`)

        writeFile(`A`.srcFor("/main/scala/Foo.scala"), Sources.`Foo.scala`)

        val fourthCompiledState = thirdCompiledState.compile(`B`)
        assertExitStatus(fourthCompiledState, ExitStatus.Ok)
        assertValidCompilationState(fourthCompiledState, List(`A`, `B`))
        assertSameExternalClassesDirs(thirdCompiledState, fourthCompiledState, List(`A`, `B`))
        assertDifferentExternalClassesDirs(compiledState, fourthCompiledState, `A`)
        assertSameExternalClassesDirs(compiledState, fourthCompiledState, `B`)
      }
    }
  }

  test("compile incrementally renamed file") {
    TestUtil.withinWorkspace { workspace =>
      def path(suffix: String) = s"/main/scala/scala/meta/internal/metals/Foo$suffix.scala"
      object Sources {
        val `Foo0.scala` =
          s"""${path("0")}
             |package scala.meta.internal.metals
             |
             |final class Foo0(conn: () => String) {
             |  def foo(s: String): String = s
             |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo0.scala`))
      val projects = List(`A`)
      val fooFilePath = `A`.srcFor(path("0"))
      writeFile(fooFilePath, Sources.`Foo0.scala`)

      def assertCorrectArtifacts(compiledState: ManagedBspTestState, suffix: String): Unit = {

        val buildProject = compiledState.toTestState.getProjectFor(`A`)
        val externalClassesDirA =
          compiledState.client.getUniqueClassesDirFor(buildProject, forceGeneration = true)

        val classFilesAfterSuccess = takeDirectorySnapshot(externalClassesDirA)
        assertEquals(
          classFilesAfterSuccess.map { classfile =>
            val pathString = classfile.path.toString()
            // drop drive letter and replace backslashes with forward slashes
            if (isWindows) pathString.drop(2).replace("\\", "/") else pathString
          },
          List(s"/scala/meta/internal/metals/Foo$suffix.class")
        )
      }

      loadBspState(workspace, projects, logger) { state =>
        val firstCompiledState = state.compile(`A`)
        assertExitStatus(firstCompiledState, ExitStatus.Ok)
        assertValidCompilationState(firstCompiledState, projects)
        assertCorrectArtifacts(firstCompiledState, "0")

        // try to rename 10 times the same file
        // VS Code will first change the file, then rename it
        val last = (1 to 10).foldLeft(firstCompiledState) { (state, num) =>
          val previousFile = `A`.srcFor(path(s"${num - 1}"))
          val newContents = Sources.`Foo0.scala`.replace(s"Foo0", s"Foo$num")
          // rename class
          writeFile(
            previousFile,
            newContents
          )
          val compiledStateChangedFile = state.compile(`A`)
          assertExitStatus(compiledStateChangedFile, ExitStatus.Ok)
          assertValidCompilationState(compiledStateChangedFile, projects)
          assertCorrectArtifacts(compiledStateChangedFile, s"$num")

          // rename file
          val newFile = fooFilePath.getParent.resolve(s"Foo$num.scala")
          Files.move(previousFile.underlying, newFile.underlying)

          val compiledStateRenameFile = compiledStateChangedFile.compile(`A`)
          assertExitStatus(compiledStateRenameFile, ExitStatus.Ok)
          assertValidCompilationState(compiledStateRenameFile, projects)

          assertCorrectArtifacts(compiledStateRenameFile, s"$num")
          compiledStateRenameFile
        }

        assertValidCompilationState(last, projects)
      }
    }
  }

  /**
   * Checks several variants regarding the previous execution of post
   * compilation tasks when the compile result is a success and when it is a
   * failure. The most important check carried out by this test is to guarantee
   * that external client classes directories are always populated with the
   * compile products of the previous successful results, even if a new BSP
   * session is established. We use semanticdb to emulate a real-world scenario.
   */
  test("successful and failed compiles always populate external classes directories") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |object A""".stripMargin
        val `A2.scala` =
          """/A.scala
            |abject A""".stripMargin
      }

      val sourceDir = workspace.resolve("a").resolve("src")
      import coursierapi._

      val semanticdbJar = Fetch
        .create()
        .addDependencies(
          Dependency.of(
            "org.scalameta",
            s"semanticdb-scalac_${BuildInfo.scalaVersion}",
            BuildTestInfo.semanticdbVersion
          )
        )
        .fetch()
        .asScala
        .collectFirst({
          case file if file.getName().toString().contains("semanticdb-scalac") => file.toString()
        })

      assert(semanticdbJar.isDefined)

      val semanticdbOpts = List(
        s"-Xplugin:${semanticdbJar.get}",
        "-Yrangepos",
        s"-P:semanticdb:sourceroot:${sourceDir}"
      )

      def semanticdbFilesFrom(classesDir: AbsolutePath): List[BloopPaths.AttributedPath] = {
        val semanticdbTarget = classesDir.resolve("META-INF").resolve("semanticdb")
        takeDirectorySnapshot(semanticdbTarget)
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`), scalacOptions = semanticdbOpts)
      val projects = List(`A`)

      var classFilesPreviousIteration: List[BloopPaths.AttributedPath] = Nil
      var semanticdbFilesPreviousIteration: List[BloopPaths.AttributedPath] = Nil
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        val buildProject = compiledState.toTestState.getProjectFor(`A`)
        val externalClassesDirA =
          compiledState.client.getUniqueClassesDirFor(buildProject, forceGeneration = true)

        // There must be three top-level paths in this dir: A.class, A$.class and META-INF
        val classFilesAfterSuccess = takeDirectorySnapshot(externalClassesDirA)
        val semanticdbFilesAfterSuccess = semanticdbFilesFrom(externalClassesDirA)
        assert(classFilesAfterSuccess.size == 3)
        assert(semanticdbFilesAfterSuccess.size == 1)

        writeFile(`A`.srcFor("/A.scala"), Sources.`A2.scala`)
        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.CompilationError)

        // There must be three top-level paths in this dir: A.class, A$.class and META-INF
        val classFilesAfterFailure = takeDirectorySnapshot(externalClassesDirA)
        assert(classFilesAfterFailure.size == 3)
        val semanticdbFilesAfterFailure = semanticdbFilesFrom(externalClassesDirA)
        assertNoDiff(
          pprint.apply(semanticdbFilesAfterSuccess, height = Int.MaxValue).render,
          pprint.apply(semanticdbFilesAfterFailure, height = Int.MaxValue).render
        )

        // Set class and semanticdb files to check them in new independent bsp connection
        classFilesPreviousIteration = classFilesAfterFailure
        semanticdbFilesPreviousIteration = semanticdbFilesAfterFailure
      }

      loadBspState(workspace, projects, logger) { newStateAfterFailure =>
        val freshCompiledState = newStateAfterFailure.compile(`A`)
        val buildProject = freshCompiledState.toTestState.getProjectFor(`A`)
        val externalClassesDirA =
          freshCompiledState.client.getUniqueClassesDirFor(buildProject, forceGeneration = true)

        val classFilesAfterFreshFailure = takeDirectorySnapshot(externalClassesDirA)
        assertNoDiff(
          pprint.apply(classFilesAfterFreshFailure, height = Int.MaxValue).render,
          pprint.apply(classFilesPreviousIteration, height = Int.MaxValue).render
        )

        val semanticdbFilesAfterFreshFailure = semanticdbFilesFrom(externalClassesDirA)
        assertNoDiff(
          pprint.apply(semanticdbFilesAfterFreshFailure, height = Int.MaxValue).render,
          pprint.apply(semanticdbFilesPreviousIteration, height = Int.MaxValue).render
        )
      }
    }
  }

  test("compile incrementally and clear previous errors") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |object A extends Base {
            |  val x = 2
            |}""".stripMargin

        val `Base.scala` =
          """/Base.scala
            |trait Base {
            |  val x: Int
            |}""".stripMargin

        val `A2.scala` =
          """/A.scala
            |object A extends Base {
            |  val x = 1
            |  val x = 2
            |}""".stripMargin

        val `A3.scala` =
          """/A.scala
            |import java.nio.file.Files
            |object A extends Base {
            |  val x = 1
            |  val x = 2
            |}""".stripMargin

        val `A4.scala` =
          """/A.scala
            |import java.nio.file.Files
            |object A extends Base {
            |  val x = 2
            |}""".stripMargin

        val `Base2.scala` =
          """/Base.scala
            |trait Base {
            |  val y: Int
            |}""".stripMargin

        val `Base3.scala` =
          """/Base.scala
            |trait Base {
            |  // Force recompilation
            |  val x: Int
            |}""".stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`A.scala`, Sources.`Base.scala`),
        scalacOptions = List("-Ywarn-unused:imports", "-Yrangepos")
      )

      val projects = List(`A`)

      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (2 Scala sources)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        writeFile(`A`.srcFor("/A.scala"), Sources.`A2.scala`)
        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
        assertInvalidCompilationState(
          secondCompiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )

        assertSameExternalClassesDirs(secondCompiledState, compiledState, projects)
        assertNoDiff(
          secondCompiledState.lastDiagnostics(`A`),
          """#2: task start 2
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#2: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(2,6),Position(2,6)),Some(Error),Some(_),Some(_),x is already defined as value x,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#2: task finish 2
            |  -> errors 1, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        writeFile(`A`.srcFor("/A.scala"), Sources.`A.scala`)
        val thirdCompiledState = secondCompiledState.compile(`A`)
        assertExitStatus(thirdCompiledState, ExitStatus.Ok)
        assertValidCompilationState(thirdCompiledState, projects)
        assertSameExternalClassesDirs(thirdCompiledState, compiledState, projects)
        assertNoDiff(
          thirdCompiledState.lastDiagnostics(`A`),
          """#3: a/src/A.scala
            |  -> List()
            |  -> reset = true
            |#3: task start 3
            |  -> Msg: Start no-op compilation for a
            |  -> Data kind: compile-task
            |#3: task finish 3
            |  -> errors 0, warnings 0, noop true
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin // no-op so it only gives the compile-report
        )

        writeFile(`A`.srcFor("/A.scala"), Sources.`A3.scala`)
        val fourthCompiledState = thirdCompiledState.compile(`A`)
        assertExitStatus(fourthCompiledState, ExitStatus.CompilationError)
        assertInvalidCompilationState(
          fourthCompiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )

        assertSameExternalClassesDirs(fourthCompiledState, compiledState, projects)
        assertNoDiff(
          fourthCompiledState.lastDiagnostics(`A`),
          """#4: task start 4
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#4: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(3,6),Position(3,6)),Some(Error),Some(_),Some(_),x is already defined as value x,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#4: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
            |  -> reset = false
            |#4: task finish 4
            |  -> errors 1, warnings 1, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        writeFile(`A`.srcFor("/A.scala"), Sources.`A4.scala`)
        val fifthCompiledState = fourthCompiledState.compile(`A`)
        assertExitStatus(fifthCompiledState, ExitStatus.Ok)
        assertValidCompilationState(fifthCompiledState, projects)
        assertDifferentExternalClassesDirs(fifthCompiledState, compiledState, projects)
        assertNoDiff(
          fifthCompiledState.lastDiagnostics(`A`),
          """#5: task start 5
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#5: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#5: task finish 5
            |  -> errors 0, warnings 1, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        writeFile(`A`.srcFor("/Base.scala"), Sources.`Base2.scala`)
        val sixthCompiledState = fifthCompiledState.compile(`A`)
        assertExitStatus(sixthCompiledState, ExitStatus.CompilationError)
        assertInvalidCompilationState(
          sixthCompiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )
        assertSameExternalClassesDirs(sixthCompiledState, fifthCompiledState, projects)
        assertNoDiff(
          sixthCompiledState.lastDiagnostics(`A`),
          """#6: task start 6
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#6: a/src/A.scala
            |  -> List()
            |  -> reset = true
            |#6: task finish 6
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            |#6: task start 6
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#6: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#6: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(1,0),Position(3,1)),Some(Error),Some(_),Some(_),object creation impossible, since value y in trait Base of type Int is not defined,None,None,Some({"actions":[]})))
            |  -> reset = false
            |#6: task finish 6
            |  -> errors 1, warnings 1, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        writeFile(`A`.srcFor("/Base.scala"), Sources.`Base3.scala`)
        val seventhCompiledState = sixthCompiledState.compile(`A`)
        assertExitStatus(seventhCompiledState, ExitStatus.Ok)
        assertValidCompilationState(seventhCompiledState, projects)
        assertDifferentExternalClassesDirs(seventhCompiledState, fifthCompiledState, projects)

        assertNoDiff(
          seventhCompiledState.lastDiagnostics(`A`),
          """#7: task start 7
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#7: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#7: task finish 7
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )
      }
    }
  }

  test("no-op compile and publish diagnostics from a previous CLI compilation") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `App.scala` =
          """/main/scala/App.scala
            |object App {
            |  def main(args: Array[String]): Unit = {
            |    "force warning in unit return type"
            |  }
            |}
          """.stripMargin
      }

      val cliLogger = new RecordingLogger(ansiCodesSupported = false)
      val bspLogger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`App.scala`))
      val projects = List(`A`)
      val cliState = loadState(workspace, projects, cliLogger)

      val cliCompiledState = cliState.compile(`A`)
      assertExitStatus(cliCompiledState, ExitStatus.Ok)
      assertValidCompilationState(cliCompiledState, projects)

      assertNoDiff(
        cliLogger.compilingInfos.mkString(lineSeparator),
        "Compiling a (1 Scala source)"
      )

      // Force a no-op via CLI to check we propagate problems from previous compile
      val secondCliCompiledState = cliCompiledState.compile(`A`)
      assertExitStatus(secondCliCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCliCompiledState, projects)
      assertSameExternalClassesDirs(cliCompiledState, secondCliCompiledState, projects)

      loadBspState(workspace, projects, bspLogger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertSameExternalClassesDirs(compiledState.toTestState, secondCliCompiledState, `A`)

        // BSP publishes warnings even if it's a no-op
        // however it skips the task start/end
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: a/src/main/scala/App.scala
            |  -> List(Diagnostic(Range(Position(2,4),Position(2,4)),Some(Warning),Some(_),Some(_),a pure expression does nothing in statement position,None,None,Some({"actions":[]})))
            |  -> reset = true""".stripMargin
        )
      }
    }
  }

  test("no-op compile and not publish diagnostics from a previous failed CLI compilation") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `App.scala` =
          """/main/scala/App.scala
            |object App {
            |  val a: String = ""
            |}
          """.stripMargin
      }

      val bspLogger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`App.scala`))
      val projects = List(`A`)

      // compile successfully in a separate bsp connection and then break
      loadBspState(workspace, projects, bspLogger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        writeFile(
          `A`.srcFor("/main/scala/App.scala"),
          """|object App {
             |  val a: String = 1
             |}
          """.stripMargin
        )
        val secondCliCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCliCompiledState, ExitStatus.CompilationError)
        assertValidCompilationState(secondCliCompiledState, projects)
      }

      // connecting and compiling should not get any previous and no longer valid diagnostics
      loadBspState(workspace, projects, bspLogger) { state =>
        // Remove the error and get back to the previous state
        writeFile(
          `A`.srcFor("/main/scala/App.scala"),
          Sources.`App.scala`
        )
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """|#1: task start 1
             |  -> Msg: Start no-op compilation for a
             |  -> Data kind: compile-task
             |#1: task finish 1
             |  -> errors 0, warnings 0, noop true
             |  -> Msg: Compiled 'a'
             |  -> Data kind: compile-report
             |""".stripMargin
        )
      }
    }
  }

  test("compile incrementally and publish warnings from a previous CLI compilation") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `App.scala` =
          """/main/scala/App.scala
            |object App {
            |  def main(args: Array[String]): Unit = {
            |    "force warning in unit return type"
            |  }
            |}
          """.stripMargin
        val `Extra.scala` =
          """/main/scala/Extra.scala
            |class Extra
          """.stripMargin
        val `Extra2.scala` =
          """/main/scala/Extra.scala
            |class Extra2
          """.stripMargin
      }

      val cliLogger = new RecordingLogger(ansiCodesSupported = false)
      val bspLogger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`App.scala`, Sources.`Extra.scala`))
      val projects = List(`A`)
      val cliState = loadState(workspace, projects, cliLogger)

      val cliCompiledState = cliState.compile(`A`)
      assertExitStatus(cliCompiledState, ExitStatus.Ok)
      assertValidCompilationState(cliCompiledState, projects)
      assertNoDiff(
        cliLogger.compilingInfos.mkString(lineSeparator),
        "Compiling a (2 Scala sources)"
      )

      loadBspState(workspace, projects, bspLogger) { state =>
        writeFile(`A`.srcFor("main/scala/Extra.scala"), Sources.`Extra2.scala`)

        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertDifferentExternalClassesDirs(compiledState.toTestState, cliCompiledState, `A`)

        // BSP client publishes warnings even if the incremental compile didn't affect that source
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: a/src/main/scala/App.scala
            |  -> List(Diagnostic(Range(Position(2,4),Position(2,4)),Some(Warning),Some(_),Some(_),a pure expression does nothing in statement position,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#1: task finish 1
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        import java.nio.file.Files
        Files.delete(`A`.srcFor("main/scala/App.scala").underlying)

        // Test that deleting a file with a warning doesn't make bloop send clear diagnostics
        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)

        assertNoDiff(
          secondCompiledState.lastDiagnostics(`A`),
          """|#2: task start 2
             |  -> Msg: Compiling a (1 Scala source)
             |  -> Data kind: compile-task
             |#2: a/src/main/scala/App.scala
             |  -> List()
             |  -> reset = true
             |#2: task finish 2
             |  -> errors 0, warnings 0, noop false
             |  -> Msg: Compiled 'a'
             |  -> Data kind: compile-report
             |""".stripMargin
        )
      }
    }
  }

  test("workspace-buildTargets-should-not-block") {
    TestUtil.withinWorkspace { workspace =>
      val `A.scala` =
        """/A.scala
          |package macros
          |
          |import scala.reflect.macros.blackbox.Context
          |import scala.language.experimental.macros
          |
          |object SleepMacro {
          |  def sleep(): Unit = macro sleepImpl
          |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
          |    import c.universe._
          |    Thread.sleep(10000)
          |    reify { () }
          |  }
          |}""".stripMargin

      val `B.scala` =
        """/B.scala
          |package example
          |object B { def foo(s: String): String = s.toString; macros.SleepMacro.sleep() }
          """.stripMargin

      val `A` = TestProject(workspace, "a", List(`A.scala`))

      val `B` = TestProject(workspace, "b", List(`B.scala`), List(`A`))

      val projects = List(`A`, `B`)

      val logger = new RecordingLogger(ansiCodesSupported = false)

      loadBspState(workspace, projects, logger) { state =>
        // First, get a baseline by calling buildTargets when no compilation is running
        val initialTargets = state.workspaceTargets
        assert(initialTargets.targets.size == 2)
        val targets = initialTargets.targets.flatMap(_.displayName).toSet
        assert(targets.contains("a") && targets.contains("b"))

        // Start compilation in the background
        val compilationHandle = state.compileHandle(`A`)

        // Wait a bit to ensure compilation has started
        Thread.sleep(100)

        // Try to get build targets while compilation is running
        val startTime = System.currentTimeMillis()
        for (_ <- 1 to 10) {
          state.workspaceTargets
        }
        val endTime = System.currentTimeMillis()
        val elapsedMs = endTime - startTime

        // The buildTargets call should return quickly (within 1 second)
        // and should not wait for compilation to complete
        if (elapsedMs >= 1000L) {
          fail(s"buildTargets took ${elapsedMs}ms, expected < 1000ms")
        }

        // Wait for compilation to complete
        val result = TestUtil.await(FiniteDuration(30, "s"))(Task.fromFuture(compilationHandle))
        assert(result.status == ExitStatus.Ok)
      }
    }
  }

  test("compile incrementally and clear old errors fixed in previous CLI compilations") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/main/scala/Foo.scala
            |object Foo {
            |  def foo(s: String): String = s
            |}
          """.stripMargin
        val `Foo2.scala` =
          """/main/scala/Foo.scala
            |object Foo {
            |  def foo(s: Int): String = s
            |}
          """.stripMargin
        val `Bar.scala` =
          """/main/scala/Bar.scala
            |object Bar {
            |  def foo(s: String): String = s
            |}
          """.stripMargin
        val `Bar2.scala` =
          """/main/scala/Bar.scala
            |object Bar {
            |  def foo(s: Int): String = s
            |}
          """.stripMargin
      }

      val cliLogger = new RecordingLogger(ansiCodesSupported = false)
      val bspLogger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo.scala`, Sources.`Bar.scala`))
      val projects = List(`A`)
      val cliState = loadState(workspace, projects, cliLogger)

      val cliCompiledState = cliState.compile(`A`)
      assertExitStatus(cliCompiledState, ExitStatus.Ok)
      assertValidCompilationState(cliCompiledState, projects)
      assertNoDiff(
        cliLogger.compilingInfos.mkString(lineSeparator),
        "Compiling a (2 Scala sources)"
      )

      loadBspState(workspace, projects, bspLogger) { state =>
        writeFile(`A`.srcFor("main/scala/Foo.scala"), Sources.`Foo2.scala`)

        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.CompilationError)
        assertInvalidCompilationState(
          compiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = false
        )

        // They must be different because the compilation in BSP didn't succeed and populate the dir
        assertDifferentExternalClassesDirs(compiledState.toTestState, cliCompiledState, `A`)

        // Reset diagnostics in `Foo` since it has disappeared in the last compilation
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: a/src/main/scala/Foo.scala
            |  -> List(Diagnostic(Range(Position(1,28),Position(1,28)),Some(Error),Some(_),Some(_),type mismatch;  found   : Int  required: String,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#1: task finish 1
            |  -> errors 1, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Fix previous compilation error in BSP in CLI client
        writeFile(`A`.srcFor("main/scala/Foo.scala"), Sources.`Foo.scala`)
        val secondCliCompiledState = compiledState.toTestStateFrom(cliCompiledState).compile(`A`)
        assertExitStatus(secondCliCompiledState, ExitStatus.Ok)
        assertValidCompilationState(secondCliCompiledState, projects)
        assertSameExternalClassesDirs(cliCompiledState, secondCliCompiledState, projects)

        writeFile(`A`.srcFor("main/scala/Bar.scala"), Sources.`Bar2.scala`)
        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.CompilationError)
        assertInvalidCompilationState(
          secondCompiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = false
        )

        assertNoDiff(
          secondCompiledState.lastDiagnostics(`A`),
          """#2: task start 2
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#2: a/src/main/scala/Bar.scala
            |  -> List(Diagnostic(Range(Position(1,28),Position(1,28)),Some(Error),Some(_),Some(_),type mismatch;  found   : Int  required: String,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#2: a/src/main/scala/Foo.scala
            |  -> List()
            |  -> reset = true
            |#2: task finish 2
            |  -> errors 1, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Fix previous compilation error in BSP in CLI client
        writeFile(`A`.srcFor("main/scala/Bar.scala"), Sources.`Bar.scala`)
        val thirdCliCompiledState =
          secondCompiledState.toTestStateFrom(secondCliCompiledState).compile(`A`)
        assertExitStatus(thirdCliCompiledState, ExitStatus.Ok)
        assertValidCompilationState(thirdCliCompiledState, projects)
        assertSameExternalClassesDirs(cliCompiledState, thirdCliCompiledState, projects)

        val thirdCompiledState = secondCompiledState.compile(`A`)
        assertExitStatus(thirdCompiledState, ExitStatus.Ok)
        assertValidCompilationState(thirdCompiledState, projects)
        assertSameExternalClassesDirs(
          thirdCliCompiledState,
          thirdCompiledState.toTestState,
          projects
        )

        assertNoDiff(
          thirdCompiledState.lastDiagnostics(`A`),
          """|#3: a/src/main/scala/Bar.scala
             |  -> List()
             |  -> reset = true
             |#3: task start 3
             |  -> Msg: Start no-op compilation for a
             |  -> Data kind: compile-task
             |#3: task finish 3
             |  -> errors 0, warnings 0, noop true
             |  -> Msg: Compiled 'a'
             |  -> Data kind: compile-report""".stripMargin
        )
      }
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
          val `Foo2.scala` =
            """/Foo.scala
              |class Foo
            """.stripMargin
          val `Foo3.scala` =
            """/Foo.scala
              |import Predef.assert
              |import Predef.Manifest
              |class Foo
            """.stripMargin
          val `Buzz.scala` =
            """/Buzz.scala
              |class Buzz
            """.stripMargin
          val `Buzz2.scala` =
            """/Buzz.scala
              |import Predef.assert
              |class Buzz
            """.stripMargin
        }

        val bspLogger = new RecordingLogger(ansiCodesSupported = false)
        val sourcesA = List(Sources.`Bar.scala`, Sources.`Foo.scala`, Sources.`Baz.scala`)
        val sourcesB = List(Sources.`Buzz.scala`)
        val options = List("-Ywarn-unused", fatalOpt)
        val `A` = TestProject(workspace, "a", sourcesA, scalacOptions = options)
        val `B` = TestProject(workspace, "b", sourcesB, List(`A`), scalacOptions = options)
        val projects = List(`A`, `B`)
        loadBspState(workspace, projects, bspLogger) { state =>
          val compiledState = state.compile(`B`)
          assertExitStatus(compiledState, ExitStatus.CompilationError)
          assertValidCompilationState(compiledState, projects)
          assertNoDiff(
            compiledState.lastDiagnostics(`A`),
            """|#1: task start 1
               |  -> Msg: Compiling a (3 Scala sources)
               |  -> Data kind: compile-task
               |#1: a/src/Foo.scala
               |  -> List(Diagnostic(Range(Position(0,0),Position(0,7)),Some(Error),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
               |  -> reset = true
               |#1: task finish 1
               |  -> errors 1, warnings 0, noop false
               |  -> Msg: Compiled 'a'
               |  -> Data kind: compile-report
               |""".stripMargin
          )

          assertNoDiff(
            compiledState.lastDiagnostics(`B`),
            """|#1: task start 2
               |  -> Msg: Compiling b (1 Scala source)
               |  -> Data kind: compile-task
               |#1: task finish 2
               |  -> errors 0, warnings 0, noop false
               |  -> Msg: Compiled 'b'
               |  -> Data kind: compile-report
               |""".stripMargin
          )

          writeFile(`A`.srcFor("/Foo.scala"), Sources.`Foo2.scala`)
          val secondCompiledState = compiledState.compile(`B`)
          assertExitStatus(secondCompiledState, ExitStatus.Ok)
          assertValidCompilationState(secondCompiledState, projects)
          assertNoDiff(
            secondCompiledState.lastDiagnostics(`A`),
            """|#2: task start 3
               |  -> Msg: Compiling a (1 Scala source)
               |  -> Data kind: compile-task
               |#2: a/src/Foo.scala
               |  -> List()
               |  -> reset = true
               |#2: task finish 3
               |  -> errors 0, warnings 0, noop false
               |  -> Msg: Compiled 'a'
               |  -> Data kind: compile-report
               |""".stripMargin
          )

          assertNoDiff(
            compiledState.lastDiagnostics(`B`),
            "" // no-op
          )

          writeFile(`A`.srcFor("/Foo.scala"), Sources.`Foo3.scala`)
          writeFile(`B`.srcFor("/Buzz.scala"), Sources.`Buzz2.scala`)
          val thirdCompiledState = secondCompiledState.compile(`B`)
          assertExitStatus(thirdCompiledState, ExitStatus.CompilationError)
          assertValidCompilationState(thirdCompiledState, projects)
          assertNoDiff(
            thirdCompiledState.lastDiagnostics(`A`),
            """|#3: task start 4
               |  -> Msg: Compiling a (1 Scala source)
               |  -> Data kind: compile-task
               |#3: a/src/Foo.scala
               |  -> List(Diagnostic(Range(Position(0,0),Position(0,7)),Some(Error),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
               |  -> reset = true
               |#3: a/src/Foo.scala
               |  -> List(Diagnostic(Range(Position(1,0),Position(1,7)),Some(Error),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
               |  -> reset = false
               |#3: task finish 4
               |  -> errors 2, warnings 0, noop false
               |  -> Msg: Compiled 'a'
               |  -> Data kind: compile-report
               |""".stripMargin
          )

          assertNoDiff(
            compiledState.lastDiagnostics(`B`),
            """|#3: task start 5
               |  -> Msg: Compiling b (1 Scala source)
               |  -> Data kind: compile-task
               |#3: b/src/Buzz.scala
               |  -> List(Diagnostic(Range(Position(0,0),Position(0,7)),Some(Error),Some(_),Some(_),Unused import,None,None,Some({"actions":[]})))
               |  -> reset = true
               |#3: task finish 5
               |  -> errors 1, warnings 0, noop false
               |  -> Msg: Compiled 'b'
               |  -> Data kind: compile-report
               |""".stripMargin
          )
        }
      }
    }

  test("add-method-during-compilation") {
    TestUtil.withinWorkspace { workspace =>
      // Macro project - must be compiled first
      val macroProject = TestProject(
        workspace,
        "macros",
        List(
          """/main/scala/SleepMacro.scala
            |package macros
            |
            |import scala.reflect.macros.blackbox.Context
            |import scala.language.experimental.macros
            |
            |object SleepMacro {
            |  def sleep(): Unit = macro sleepImpl
            |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
            |    import c.universe._
            |    // Sleep to create compilation window
            |    Thread.sleep(3000)
            |    reify { () }
            |  }
            |}
          """.stripMargin
        )
      )
      // Fast file that will be modified to break compilation if stale bytecode is used
      val fastFile =
        """/main/scala/a/FastCompilation.scala
          |package a
          |object FastCompilation {
          |  def method1(): String = "initial version"
          |}
        """.stripMargin
      // Slow file that uses the macro AND depends on the fast file
      val slowFile =
        """/main/scala/a/SlowCompilation.scala
          |package a
          |object SlowCompilation {
          |  def slowMethod(): Unit = {
          |    macros.SleepMacro.sleep()
          |    val result = FastCompilation.method1()
          |    println(s"Slow compilation complete: $result")
          |  }
          |}
        """.stripMargin
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val testProject = TestProject(
        workspace,
        "a",
        List(slowFile, fastFile),
        List(macroProject),
        scalaVersion = Some("2.13.17")
      )
      val projects = List(macroProject, testProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val bloopState = TestUtil.loadTestProject(configDir.underlying, logger)
      val macroState1 = new TestState(bloopState)
      // First compile the macro project
      val macroCompiled = macroState1.compile(macroProject)
      assert(macroCompiled.status == ExitStatus.Ok)
      def runTest(sleepBeforeModifyFile: Long): Unit = {
        writeFile(testProject.srcFor("main/scala/a/SlowCompilation.scala"), slowFile)
        writeFile(testProject.srcFor("main/scala/a/FastCompilation.scala"), fastFile)
        // Start compilation of main project asynchronously
        val projectState1 = new TestState(TestUtil.loadTestProject(configDir.underlying, logger))
        val compilationTask = Task {
          projectState1.compile(testProject)
        }.runAsync(bloop.engine.ExecutionContext.ioScheduler)
        // Sleep just long enough for Zinc to read files into memory
        // but modify during the macro sleep (3000ms) in hopes of hitting bug where Zinc re-reads the file,
        // post-compile, as part of generating the analysis.
        Thread.sleep(sleepBeforeModifyFile)
        require(!compilationTask.isCompleted, "Slept too long! First compile already completed!")
        val modifiedFastFile =
          """/main/scala/a/FastCompilation.scala
            |package a
            |object FastCompilation {
            |  def method1(): String = "1" // OLD METHOD
            |  def method2(): String = "2" // NEW METHOD!
            |}
        """.stripMargin
        // writeFile helper actually deletes and recreates
        writeFile(testProject.srcFor("main/scala/a/FastCompilation.scala"), modifiedFastFile)
        // Wait for first compilation to complete
        scala.concurrent.Await.result(compilationTask, FiniteDuration(20, TimeUnit.SECONDS))
        // Now modify downstream file and compile again. If there's a race, this compile might not recompile FastCompilation
        // even though it's necessary, given that method2 was missed.
        val modifiedSlowFile =
          """/main/scala/a/SlowCompilation.scala
            |package a
            |object SlowCompilation {
            |  def slowMethod(): Unit = {
            |    macros.SleepMacro.sleep()
            |    val result = FastCompilation.method2() // CHANGED USAGE!
            |    println(s"Slow compilation complete: $result")
            |  }
            |}
        """.stripMargin
        writeFile(testProject.srcFor("main/scala/a/SlowCompilation.scala"), modifiedSlowFile)
        val projectState2 = new TestState(TestUtil.loadTestProject(configDir.underlying, logger))
        val secondResult = projectState2.compile(testProject)
        assert(secondResult.status == ExitStatus.Ok)
      }
      runTest(2000)
    }
  }

  test("task notifications are sent when compiling project with dependency") {
    TestUtil.withinWorkspace { workspace =>
      val sourcesA = List(
        """/main/scala/Foo.scala
          |class Foo {
          |  def foo(s: String): String = s
          |}
        """.stripMargin
      )

      val sourcesB = List(
        """/main/scala/Bar.scala
          |object Bar {
          |  def bar(s: String): String = (new Foo()).foo(s)
          |}
        """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sourcesA)
      val `B` = TestProject(workspace, "b", sourcesB, List(`A`))
      val projects = List(`A`, `B`)

      loadBspState(workspace, projects, logger) { state =>
        // When we compile B, which depends on A, both should get task notifications
        val compiledState = state.compile(`B`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        // Verify A gets task start and task finish notifications
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Verify B gets task start and task finish notifications with different task IDs
        assertNoDiff(
          compiledState.lastDiagnostics(`B`),
          """#1: task start 2
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 2
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report""".stripMargin
        )

      }
    }
  }

  test("task notifications are sent when compiling dependency chain A -> B -> C") {
    TestUtil.withinWorkspace { workspace =>
      val sourcesA = List(
        """/main/scala/A.scala
          |class A {
          |  def methodA(): String = "A"
          |}
        """.stripMargin
      )

      val sourcesB = List(
        """/main/scala/B.scala
          |class B {
          |  def methodB(): String = (new A()).methodA() + "B"
          |}
        """.stripMargin
      )

      val sourcesC = List(
        """/main/scala/C.scala
          |object C {
          |  def methodC(): String = (new B()).methodB() + "C"
          |}
        """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sourcesA)
      val `B` = TestProject(workspace, "b", sourcesB, List(`A`))
      val `C` = TestProject(workspace, "c", sourcesC, List(`B`))
      val projects = List(`A`, `B`, `C`)

      loadBspState(workspace, projects, logger) { state =>
        // When we compile C, which depends on B -> A, all three should get task notifications
        val compiledState = state.compile(`C`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        // Verify A gets task notifications (task id 1)
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Verify B gets task notifications (task id 2)
        assertNoDiff(
          compiledState.lastDiagnostics(`B`),
          """#1: task start 2
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 2
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Verify C gets task notifications (task id 3)
        assertNoDiff(
          compiledState.lastDiagnostics(`C`),
          """#1: task start 3
            |  -> Msg: Compiling c (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 3
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'c'
            |  -> Data kind: compile-report""".stripMargin
        )
      }
    }
  }

  test("send-noop") {
    TestUtil.withinWorkspace { workspace =>
      val sourcesA = List(
        """/main/scala/Foo.scala
          |class Foo
        """.stripMargin
      )

      val sourcesB = List(
        """/main/scala/Bar.scala
          |class Bar
        """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sourcesA)
      val `B` = TestProject(workspace, "b", sourcesB, List(`A`))
      val projects = List(`A`, `B`)

      loadBspState(workspace, projects, logger) { state =>
        // First compilation
        val compiledState = state.compile(`B`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        // Second compilation (no-op) - should not send task notifications for either project
        val secondCompiledState = compiledState.compile(`B`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)

        // Verify noop task notifications for A
        assertNoDiff(
          secondCompiledState.lastDiagnostics(`A`),
          ""
        )

        // Verify noop task notifications for B
        assertNoDiff(
          secondCompiledState.lastDiagnostics(`B`),
          ""
        )

      }
    }
  }

  test("task notifications sent when only dependency needs recompilation") {
    TestUtil.withinWorkspace { workspace =>
      val sourcesA = List(
        """/main/scala/Foo.scala
          |class Foo {
          |  def foo(): String = "original"
          |}
        """.stripMargin
      )

      val sourcesB = List(
        """/main/scala/Bar.scala
          |class Bar {
          |  def bar(): String = (new Foo()).foo()
          |}
        """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sourcesA)
      val `B` = TestProject(workspace, "b", sourcesB, List(`A`))
      val projects = List(`A`, `B`)

      loadBspState(workspace, projects, logger) { state =>
        // First compilation
        val compiledState = state.compile(`B`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        // Modify only A
        writeFile(
          `A`.srcFor("/main/scala/Foo.scala"),
          """/main/scala/Foo.scala
            |class Foo {
            |  def foo(): String = "modified"
            |}
          """.stripMargin
        )

        // Compile B again - should trigger recompilation of A and B
        val secondCompiledState = compiledState.compile(`B`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)

        // Verify A gets task notifications (modified and recompiled)
        assertNoDiff(
          secondCompiledState.lastDiagnostics(`A`),
          """#2: task start 3
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#2: task finish 3
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Verify B gets task notifications (needs recompilation due to A change)
        assertNoDiff(
          secondCompiledState.lastDiagnostics(`B`),
          """#2: task start 4
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#2: task finish 4
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report""".stripMargin
        )
      }
    }
  }

}
