package bloop.bsp

import bloop.engine.State
import bloop.config.Config
import bloop.cli.{ExitStatus, BspProtocol}
import bloop.util.{TestUtil, TestProject}
import bloop.logging.RecordingLogger
import bloop.internal.build.BuildInfo
import java.nio.file.attribute.FileTime
import bloop.io.AbsolutePath
import bloop.io.{Paths => BloopPaths}
import java.nio.file.Files
import monix.eval.Task
import bloop.engine.ExecutionContext
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import ch.epfl.scala.bsp.ScalacOptionsItem

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
        assert(state.status == ExitStatus.Ok)
      }
    }
    val jsonrpc = logger.debugs.filter(_.startsWith(" -->"))
    // Filter out the initialize request that contains platform-specific details
    val allButInitializeRequest = jsonrpc.filterNot(_.contains("""build/initialize""""))
    assertNoDiff(
      allButInitializeRequest.mkString(System.lineSeparator),
      s"""| --> {
          |  "result" : {
          |    "displayName" : "${BuildInfo.bloopName}",
          |    "version" : "${BuildInfo.version}",
          |    "bspVersion" : "${BuildInfo.bspVersion}",
          |    "capabilities" : {
          |      "compileProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "testProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "runProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "inverseSourcesProvider" : true,
          |      "dependencySourcesProvider" : true,
          |      "resourcesProvider" : true,
          |      "buildTargetChangedProvider" : false
          |    },
          |    "data" : null
          |  },
          |  "id" : "2",
          |  "jsonrpc" : "2.0"
          |}
          | --> {
          |  "method" : "build/initialized",
          |  "params" : {
          |    
          |  },
          |  "jsonrpc" : "2.0"
          |}
          | --> {
          |  "method" : "build/shutdown",
          |  "params" : {
          |    
          |  },
          |  "id" : "3",
          |  "jsonrpc" : "2.0"
          |}
          | --> {
          |  "result" : {
          |    
          |  },
          |  "id" : "3",
          |  "jsonrpc" : "2.0"
          |}
          | --> {
          |  "method" : "build/exit",
          |  "params" : {
          |    
          |  },
          |  "jsonrpc" : "2.0"
          |}""".stripMargin
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
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin,
          compiledState.lastDiagnostics(`A`)
        )

        val secondCompiledState = compiledState.compile(`A`)
        assert(secondCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)
        assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
        assertNoDiff(
          """#2: task start 2
            |  -> Msg: Start no-op compilation for a
            |  -> Data kind: compile-task
            |#2: task finish 2
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin,
          secondCompiledState.lastDiagnostics(`A`)
        )
      }
    }
  }

  test("create orphan client classes directory and make sure loading a BSP session cleans it up") {
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
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // Add extra client classes directory
      val projectA = compiledState.getProjectFor(`A`)
      val bspClientsRootDir = projectA.bspClientClassesRootDirectory
      val orphanClientClassesDirName = projectA.genericClassesDir.underlying.getFileName().toString
      val orphanClientClassesDir =
        bspClientsRootDir.resolve(s"$orphanClientClassesDirName-test-123aAfd12i23")
      Files.createDirectories(orphanClientClassesDir.underlying)

      loadBspState(workspace, projects, logger) { bspState =>
        // Ask for scala options to force client to create a client classes dir for `A`
        val _ = bspState.scalaOptions(`A`)
      }

      // Wait until the extra directory is finally deleted at the end of the bsp session
      TestUtil.await(
        FiniteDuration(2, TimeUnit.SECONDS),
        bloop.engine.ExecutionContext.ioScheduler
      ) {
        Task {
          var check: Boolean = true
          while (check) {
            // The task cleaning up client classes directories should have removed the extra dir
            check = orphanClientClassesDir.exists
            Thread.sleep(100)
          }
        }.timeoutTo(
          FiniteDuration(2, TimeUnit.SECONDS),
          Task(sys.error(s"Expected deletion of $orphanClientClassesDir"))
        )
      }
    }
  }

  test("use client root classes directory and make sure project directories are stable") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)

      val userClientClassesRootDir = workspace.resolve("root-client-dirs")
      var firstScalacOptions: List[ScalacOptionsItem] = Nil
      var secondScalacOptions: List[ScalacOptionsItem] = Nil
      // Start first client and query for scalac options which creates client classes dirs
      loadBspState(workspace, projects, logger, Some(userClientClassesRootDir)) { bspState =>
        val (_, options) = bspState.scalaOptions(`A`)
        firstScalacOptions = options.items
        firstScalacOptions.foreach(d => assertIsDirectory(AbsolutePath(d.classDirectory.toPath)))
      }

      // Start second client and query for scalac options which should use same dirs as before
      loadBspState(workspace, projects, logger, Some(userClientClassesRootDir)) { bspState =>
        val (_, options) = bspState.scalaOptions(`A`)
        secondScalacOptions = options.items
        secondScalacOptions.foreach(d => assertIsDirectory(AbsolutePath(d.classDirectory.toPath)))
      }

      firstScalacOptions.zip(secondScalacOptions).foreach {
        case (firstItem, secondItem) =>
          assertNoDiff(
            firstItem.classDirectory.value,
            secondItem.classDirectory.value
          )
      }

      firstScalacOptions.foreach { option =>
        assertIsDirectory(AbsolutePath(option.classDirectory.toPath))
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
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        assertNoDiff(
          compiledState.lastDiagnostics(`B`),
          """#1: task start 2
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#1: task finish 2
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report""".stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/main/scala/Foo.scala"), Sources.`Foo2.scala`))

        val secondCompiledState = compiledState.compile(`B`)
        assert(secondCompiledState.status == ExitStatus.CompilationError)
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
            |  -> List(Diagnostic(Range(Position(2,28),Position(2,28)),Some(Error),None,None,type mismatch;  found   : Int  required: String,None))
            |  -> reset = true
            |#2: task finish 3
            |  -> errors 1, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report """.stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/main/scala/Foo.scala"), Sources.`Foo3.scala`))

        val thirdCompiledState = secondCompiledState.compile(`B`)
        assert(thirdCompiledState.status == ExitStatus.CompilationError)
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

        assertIsFile(writeFile(`A`.srcFor("/main/scala/Foo.scala"), Sources.`Foo.scala`))

        val fourthCompiledState = thirdCompiledState.compile(`B`)
        assert(fourthCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(fourthCompiledState, List(`A`, `B`))
        assertSameExternalClassesDirs(thirdCompiledState, fourthCompiledState, List(`A`, `B`))
        assertDifferentExternalClassesDirs(compiledState, fourthCompiledState, `A`)
        assertSameExternalClassesDirs(compiledState, fourthCompiledState, `B`)
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

      // Change the semanticdb jar every time we upgrade Scala version
      assert(BuildInfo.scalaVersion == "2.12.8")
      val sourceDir = workspace.resolve("a").resolve("src")
      val semanticdbJar = unsafeGetResource("semanticdb_2.12.8-4.1.11.jar")
      val semanticdbOpts = List(
        s"-Xplugin:$semanticdbJar",
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
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        val buildProject = compiledState.toTestState.getProjectFor(`A`)
        val externalClassesDirA = compiledState.client.getUniqueClassesDirFor(buildProject)

        // There must be three top-level paths in this dir: A.class, A$.class and META-INF
        val classFilesAfterSuccess = takeDirectorySnapshot(externalClassesDirA)
        val semanticdbFilesAfterSuccess = semanticdbFilesFrom(externalClassesDirA)
        assert(classFilesAfterSuccess.size == 3)
        assert(semanticdbFilesAfterSuccess.size == 1)

        writeFile(`A`.srcFor("/A.scala"), Sources.`A2.scala`)
        val secondCompiledState = compiledState.compile(`A`)
        assert(secondCompiledState.status == ExitStatus.CompilationError)

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
        val externalClassesDirA = freshCompiledState.client.getUniqueClassesDirFor(buildProject)

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
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (2 Scala sources)
            |  -> Data kind: compile-task
            |#1: task finish 1
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/A.scala"), Sources.`A2.scala`))
        val secondCompiledState = compiledState.compile(`A`)
        assert(secondCompiledState.status == ExitStatus.CompilationError)
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
            |  -> List(Diagnostic(Range(Position(2,6),Position(2,6)),Some(Error),None,None,x is already defined as value x,None))
            |  -> reset = true
            |#2: task finish 2
            |  -> errors 1, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/A.scala"), Sources.`A.scala`))
        val thirdCompiledState = secondCompiledState.compile(`A`)
        assert(thirdCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(thirdCompiledState, projects)
        assertSameExternalClassesDirs(thirdCompiledState, compiledState, projects)
        assertNoDiff(
          thirdCompiledState.lastDiagnostics(`A`),
          """#3: task start 3
            |  -> Msg: Start no-op compilation for a
            |  -> Data kind: compile-task
            |#3: a/src/A.scala
            |  -> List()
            |  -> reset = true
            |#3: task finish 3
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/A.scala"), Sources.`A3.scala`))
        val fourthCompiledState = thirdCompiledState.compile(`A`)
        assert(fourthCompiledState.status == ExitStatus.CompilationError)
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
            |  -> List(Diagnostic(Range(Position(3,6),Position(3,6)),Some(Error),None,None,x is already defined as value x,None))
            |  -> reset = true
            |#4: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),None,None,Unused import,None))
            |  -> reset = false
            |#4: task finish 4
            |  -> errors 1, warnings 1
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/A.scala"), Sources.`A4.scala`))
        val fifthCompiledState = fourthCompiledState.compile(`A`)
        assert(fifthCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(fifthCompiledState, projects)
        assertDifferentExternalClassesDirs(fifthCompiledState, compiledState, projects)
        assertNoDiff(
          fifthCompiledState.lastDiagnostics(`A`),
          """#5: task start 5
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#5: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),None,None,Unused import,None))
            |  -> reset = true
            |#5: task finish 5
            |  -> errors 0, warnings 1
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/Base.scala"), Sources.`Base2.scala`))
        val sixthCompiledState = fifthCompiledState.compile(`A`)
        assert(sixthCompiledState.status == ExitStatus.CompilationError)
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
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            |#6: task start 6
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#6: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),None,None,Unused import,None))
            |  -> reset = true
            |#6: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(1,0),Position(3,1)),Some(Error),None,None,object creation impossible, since value y in trait Base of type Int is not defined,None))
            |  -> reset = false
            |#6: task finish 6
            |  -> errors 1, warnings 1
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        assertIsFile(writeFile(`A`.srcFor("/Base.scala"), Sources.`Base3.scala`))
        val seventhCompiledState = sixthCompiledState.compile(`A`)
        assert(seventhCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(seventhCompiledState, projects)
        assertDifferentExternalClassesDirs(seventhCompiledState, fifthCompiledState, projects)

        assertNoDiff(
          seventhCompiledState.lastDiagnostics(`A`),
          """#7: task start 7
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#7: a/src/A.scala
            |  -> List(Diagnostic(Range(Position(0,0),Position(0,26)),Some(Warning),None,None,Unused import,None))
            |  -> reset = true
            |#7: task finish 7
            |  -> errors 0, warnings 0
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
      assert(cliCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(cliCompiledState, projects)

      assertNoDiff(
        cliLogger.compilingInfos.mkString(System.lineSeparator),
        "Compiling a (1 Scala source)"
      )

      // Force a no-op via CLI to check we propagate problems from previous compile
      val secondCliCompiledState = cliCompiledState.compile(`A`)
      assert(secondCliCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(secondCliCompiledState, projects)
      assertSameExternalClassesDirs(cliCompiledState, secondCliCompiledState, projects)

      loadBspState(workspace, projects, bspLogger) { state =>
        val compiledState = state.compile(`A`)
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertSameExternalClassesDirs(compiledState.toTestState, secondCliCompiledState, `A`)

        // BSP publishes warnings even if it's a no-op
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Start no-op compilation for a
            |  -> Data kind: compile-task
            |#1: a/src/main/scala/App.scala
            |  -> List(Diagnostic(Range(Position(2,4),Position(2,4)),Some(Warning),None,None,a pure expression does nothing in statement position,None))
            |  -> reset = true
            |#1: task finish 1
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
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
      assert(cliCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(cliCompiledState, projects)
      assertNoDiff(
        cliLogger.compilingInfos.mkString(System.lineSeparator),
        "Compiling a (2 Scala sources)"
      )

      loadBspState(workspace, projects, bspLogger) { state =>
        assertIsFile(writeFile(`A`.srcFor("main/scala/Extra.scala"), Sources.`Extra2.scala`))

        val compiledState = state.compile(`A`)
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertDifferentExternalClassesDirs(compiledState.toTestState, cliCompiledState, `A`)

        // BSP client publishes warnings even if the incremental compile didn't affect that source
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: a/src/main/scala/App.scala
            |  -> List(Diagnostic(Range(Position(2,4),Position(2,4)),Some(Warning),None,None,a pure expression does nothing in statement position,None))
            |  -> reset = true
            |#1: task finish 1
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        import java.nio.file.Files
        Files.delete(`A`.srcFor("main/scala/App.scala").underlying)

        // Test that deleting a file with a warning doesn't make bloop send clear diagnostics
        val secondCompiledState = compiledState.compile(`A`)
        assert(secondCompiledState.status == ExitStatus.Ok)
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
             |  -> errors 0, warnings 0
             |  -> Msg: Compiled 'a'
             |  -> Data kind: compile-report
             |""".stripMargin
        )
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
      assert(cliCompiledState.status == ExitStatus.Ok)
      assertValidCompilationState(cliCompiledState, projects)
      assertNoDiff(
        cliLogger.compilingInfos.mkString(System.lineSeparator),
        "Compiling a (2 Scala sources)"
      )

      loadBspState(workspace, projects, bspLogger) { state =>
        assertIsFile(writeFile(`A`.srcFor("main/scala/Foo.scala"), Sources.`Foo2.scala`))

        val compiledState = state.compile(`A`)
        assert(compiledState.status == ExitStatus.CompilationError)
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
            |  -> List(Diagnostic(Range(Position(1,28),Position(1,28)),Some(Error),None,None,type mismatch;  found   : Int  required: String,None))
            |  -> reset = true
            |#1: task finish 1
            |  -> errors 1, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Fix previous compilation error in BSP in CLI client
        assertIsFile(writeFile(`A`.srcFor("main/scala/Foo.scala"), Sources.`Foo.scala`))
        val secondCliCompiledState = compiledState.toTestStateFrom(cliCompiledState).compile(`A`)
        assert(secondCliCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(secondCliCompiledState, projects)
        assertSameExternalClassesDirs(cliCompiledState, secondCliCompiledState, projects)

        assertIsFile(writeFile(`A`.srcFor("main/scala/Bar.scala"), Sources.`Bar2.scala`))
        val secondCompiledState = compiledState.compile(`A`)
        assert(secondCompiledState.status == ExitStatus.CompilationError)
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
            |  -> List(Diagnostic(Range(Position(1,28),Position(1,28)),Some(Error),None,None,type mismatch;  found   : Int  required: String,None))
            |  -> reset = true
            |#2: a/src/main/scala/Foo.scala
            |  -> List()
            |  -> reset = true
            |#2: task finish 2
            |  -> errors 1, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )

        // Fix previous compilation error in BSP in CLI client
        assertIsFile(writeFile(`A`.srcFor("main/scala/Bar.scala"), Sources.`Bar.scala`))
        val thirdCliCompiledState =
          secondCompiledState.toTestStateFrom(secondCliCompiledState).compile(`A`)
        assert(thirdCliCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(thirdCliCompiledState, projects)
        assertSameExternalClassesDirs(cliCompiledState, thirdCliCompiledState, projects)

        val thirdCompiledState = secondCompiledState.compile(`A`)
        assert(thirdCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(thirdCompiledState, projects)
        assertSameExternalClassesDirs(
          thirdCliCompiledState,
          thirdCompiledState.toTestState,
          projects
        )

        assertNoDiff(
          thirdCompiledState.lastDiagnostics(`A`),
          """#3: task start 3
            |  -> Msg: Start no-op compilation for a
            |  -> Data kind: compile-task
            |#3: a/src/main/scala/Bar.scala
            |  -> List()
            |  -> reset = true
            |#3: task finish 3
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report""".stripMargin
        )
      }
    }
  }
}
