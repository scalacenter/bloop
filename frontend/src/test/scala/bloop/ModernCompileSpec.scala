package bloop

import bloop.config.Config
import bloop.io.{AbsolutePath, Paths => BloopPaths}
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

object ModernCompileSpec extends bloop.testing.BaseSuite {
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
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
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
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)
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
        logger.compilingInfos.sorted.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling b (1 Scala source)""".stripMargin
      )

      assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A2.scala`))
      val secondCompiledState = compiledState.compile(`B`)
      assert(secondCompiledState.status == ExitStatus.CompilationError)
      assertSameExternalClassesDirs(compiledState, secondCompiledState, projects)

      assertInvalidCompilationState(
        secondCompiledState,
        projects,
        existsAnalysisFile = true,
        hasPreviousSuccessful = true,
        hasSameContentsInClassesDir = true
      )

      assertNoDiff(
        logger.renderErrors(exceptContaining = "failed to compile"),
        """[E1] b/src/B.scala:2:17
          |     type mismatch;
          |      found   : String("")
          |      required: Int
          |     L2:   println(A.foo(""))
          |                         ^
          |b/src/B.scala: L2 [E1]""".stripMargin
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
    }
  }

  test("compile a build with diamond shape and check basic compilation invariants") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` = "/A.scala\npackage p0\nclass A"
        val `B.scala` = "/B.scala\npackage p1\nimport p0.A\nclass B extends A"
        val `C.scala` = "/C.scala\npackage p2\nimport p0.A\nclass C extends A"
        val `D.scala` = "/D.scala\npackage p3\ntrait D"
        val `E.scala` =
          "/E.scala\npackage p4\nimport p1.B\nimport p2.C\nimport p3.D\nobject E extends B with D"
        val `F.scala` = "/F.scala\npackage p5\nimport p3.NotFound\nclass F"
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
      assertValidCompilationState(compiledState, projects)

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

      assertNoDiff(
        """
          |[E2] a/src/main/scala/Bar.scala:2:22
          |     not found: type Foo
          |     L2:   val foo: Foo = new Foo
          |                              ^
          |[E1] a/src/main/scala/Bar.scala:2:12
          |     not found: type Foo
          |     L2:   val foo: Foo = new Foo
          |                    ^
          |a/src/main/scala/Bar.scala: L2 [E1], L2 [E2]
          """.stripMargin,
        logger.renderErrors(exceptContaining = "failed to compile")
      )

      assertIsFile(writeFile(`A`.srcFor("main/scala/Bar.scala"), Sources.`Bar2.scala`))
      val thirdCompiledState = secondCompiledState.compile(`A`)
      assert(thirdCompiledState.status == ExitStatus.Ok)
      // Checks that we remove `Foo.class` from the external classes dir which is critical
      assertValidCompilationState(thirdCompiledState, projects)
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

      assertNoDiff(
        """
          |[E2] c/src/main/scala/Bar.scala:2:22
          |     not found: type Foo
          |     L2:   val foo: Foo = new Foo
          |                              ^
          |[E1] c/src/main/scala/Bar.scala:2:12
          |     not found: type Foo
          |     L2:   val foo: Foo = new Foo
          |                    ^
          |c/src/main/scala/Bar.scala: L2 [E1], L2 [E2]
          """.stripMargin,
        logger.renderErrors(exceptContaining = "failed to compile")
      )

      assertIsFile(writeFile(`C`.srcFor("main/scala/Bar.scala"), Sources.`Bar2.scala`))
      val thirdCompiledState = secondCompiledState.compile(`C`)
      assert(thirdCompiledState.status == ExitStatus.Ok)
      // Checks that we remove `Foo.class` from the external classes dir which is critical
      assertValidCompilationState(thirdCompiledState, projects)
    }
  }

  test("report java errors when `JavaThenScala` is enabled") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` = "/A.scala\nclass A"
        val `B.java` = "/B.java\npublic class B extends A {}"
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

      val cannotFindSymbolError: String = {
        if (bloop.bsp.BspServer.isMac) {
          """[E1] a/src/B.java:1
            |     cannot find symbol
            |       symbol: class A
            |a/src/B.java: L1 [E1]""".stripMargin
        } else {
          // TODO: Figure out why error is different and why `A` is not in the msg
          """[E1] a/src/B.java:1
            |      error: cannot find symbol
            |a/src/B.java: L1 [E1]""".stripMargin
        }
      }

      assertDiagnosticsResult(compiledState.getLastResultFor(`A`), 1)
      assertNoDiff(
        logger.renderErrors(exceptContaining = "failed to compile"),
        cannotFindSymbolError
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

      assertDiagnosticsResult(compiledState.getLastResultFor(`A`), 1)
      assertNoDiff(
        logger.renderErrors(exceptContaining = "failed to compile"),
        """[E1] a/src/Foo.scala:2:18
          |     ';' expected but '=' found.
          |     L2:   al foo: String = 1
          |                          ^
          |a/src/Foo.scala: L2 [E1]""".stripMargin
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
        val `F.scala` = "/F.scala\npackage p0\nclass F"
        val `H.scala` = "/H.scala\npackage p1\nimport p0.F\nclass H extends F"
        val `G.scala` = "/G.scala\npackage p3\ntrait G"
        val `I.scala` = "/I.scala\npackage p2\nimport p1.H\nimport p3.G\nclass I extends H with G"
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

  test("cancel compilation with expensive compilation time") {
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
        try Await.result(backgroundCompiledUserState, Duration(2500, "ms"))
        catch {
          case scala.util.control.NonFatal(t) => backgroundCompiledUserState.cancel(); throw t
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
      assert(compiledState.status == ExitStatus.CompilationError)
      assertNoDiff(
        logger.errors.mkString(System.lineSeparator()),
        s"""
           |[E2] ${TestUtil.universalPath("a/src/main/scala/A.scala")}:6:14
           |     type mismatch;
           |      found   : String
           |      required: Int
           |     L6:     substring(0))
           |                      ^
           |[E1] ${TestUtil.universalPath("a/src/main/scala/A.scala")}:2:33
           |     type mismatch;
           |      found   : String
           |      required: Int
           |     L2:   "".lengthCompare("1".substring(0))
           |                            ^^^^^^^^^^^^^^^^
           |${TestUtil.universalPath("a/src/main/scala/A.scala")}: L2 [E1], L6 [E2]
           |'a' failed to compile.""".stripMargin
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
      assertNoDiff(
        logger.errors.mkString(System.lineSeparator),
        """[E2] a/src/A.scala:3:17
          |     type mismatch;
          |      found   : String("")
          |      required: Int
          |     L3:   val a2: Int = ""
          |                         ^
          |[E1] a/src/A.scala:2:17
          |     type mismatch;
          |      found   : String("")
          |      required: Int
          |     L2:   val a1: Int = ""
          |                         ^
          |a/src/A.scala: L2 [E1], L3 [E2]
          |'a' failed to compile.""".stripMargin
      )
    }
  }
}
