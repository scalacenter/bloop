package bloop

import bloop.Compiler.Result.Success
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.internal.build.BuildTestInfo
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.CrossPlatform
import bloop.util.TestProject
import bloop.util.TestUtil

object SourceGeneratorSpec extends bloop.testing.BaseSuite {

  private val generator: List[String] =
    if (CrossPlatform.isWindows) List("python", BuildTestInfo.sampleSourceGenerator.getAbsolutePath)
    else List(BuildTestInfo.sampleSourceGenerator.getAbsolutePath)

  test("compile a project having a source generator") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, project, state) =>
      writeFile(project.srcFor("Foo.scala", exists = false), assertNInputs(n = 3))
      writeFile(workspace.resolve("file_one.in"), "file one")
      writeFile(workspace.resolve("file_two.in"), "file two")
      writeFile(workspace.resolve("file_three.in"), "file three")
      writeFile(workspace.resolve("excluded.whatever"), "this one is excluded")

      val compiledState = state.compile(project)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, project :: Nil)
    }
  }

  test("compile projects with dependent source generators") {
    TestUtil.withinWorkspace { workspace =>
      val sourcesA = List(
        """/main/scala/Foo.scala
          |class Foo {
          |  val expectedArgsLength = generated.NameLengths.args_3
          |  val test = generated.NameLengths.nameLengths
          |}""".stripMargin
      )
      val generatedSourcesA = workspace.resolve("source-generator-output-A").underlying
      val sourceGeneratorA = Config.SourceGenerator(
        sourcesGlobs = List(
          Config.SourcesGlobs(workspace.underlying, None, "glob:*.test_input" :: Nil, Nil)
        ),
        outputDirectory = generatedSourcesA,
        command = generator
      )
      val `A` = TestProject(workspace, "a", sourcesA, sourceGenerators = sourceGeneratorA :: Nil)

      // The inputs of the source generator of project A
      List(
        "file_one.test_input",
        "file_two.test_input",
        "file_three.test_input",
        "excluded_file.whatever"
      ).foreach { file =>
        workspace.resolve(file).toFile.createNewFile()
      }

      val sourcesB = List(
        """/main/scala/Foo.scala
          |class Foo {
          |  val expectedArgsLength = generated.NameLengths.args_1
          |  val test = generated.NameLengths.nameLengths
          |}""".stripMargin
      )
      val generatedSourcesB = workspace.resolve("source-generator-output-B").underlying
      val sourceGeneratorB = Config.SourceGenerator(
        sourcesGlobs = List(
          Config.SourcesGlobs(generatedSourcesA, None, "glob:*.scala" :: Nil, Nil)
        ),
        outputDirectory = generatedSourcesB,
        command = generator
      )
      val `B` = TestProject(
        workspace,
        "b",
        sourcesB,
        directDependencies = `A` :: Nil,
        sourceGenerators = sourceGeneratorB :: Nil
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`B`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }

  test("source generator failure yields compilation failure") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val sourceGenerator = Config.SourceGenerator(
        sourcesGlobs = Nil,
        outputDirectory = workspace.underlying.resolve("source-generator-output"),
        command = generator ++ List("fail_now")
      )
      val `A` = TestProject(workspace, "a", Nil, sourceGenerators = sourceGenerator :: Nil)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.CompilationError)
    }
  }

  test("source generator is run when there are no matching input files") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (_, project, state) =>
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 0))
      val compiledState = state.compile(project)
      assertExitStatus(compiledState, ExitStatus.Ok)
    }
  }

  test("source generator is re-run when an input file is removed") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, project, state) =>
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      assertExitStatus(compiledState1, ExitStatus.Ok)

      workspace.resolve("hello.in").toFile.delete()
      writeFile(project.srcFor("test.scala"), assertNInputs(n = 0))

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)
    }
  }

  test("source generator is re-run when an input file is added") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, project, state) =>
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      assertExitStatus(compiledState1, ExitStatus.Ok)

      writeFile(workspace.resolve("goodbye.in"), "goodbye")
      writeFile(project.srcFor("test.scala"), assertNInputs(n = 2))

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)
    }
  }

  test("source generator is re-run when an input file is modified") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, project, state) =>
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = sourceHashFor("NameLengths_1.scala", project, compiledState1)
      assertExitStatus(compiledState1, ExitStatus.Ok)

      writeFile(workspace.resolve("hello.in"), "goodbye")

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = sourceHashFor("NameLengths_1.scala", project, compiledState2)
      assertNotEquals(origHash, newHash)
    }
  }

  test("source generator is re-run when an output file is modified") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, project, state) =>
      val generatorOutput = project.config.sourceGenerators
        .flatMap(_.headOption)
        .map(p => AbsolutePath(p.outputDirectory))
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = sourceHashFor("NameLengths_1.scala", project, compiledState1)
      assertExitStatus(compiledState1, ExitStatus.Ok)

      generatorOutput.foreach { out =>
        writeFile(out.resolve("NameLengths_1.scala"), "won't compile -- must rerun generator.")
      }

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = sourceHashFor("NameLengths_1.scala", project, compiledState2)
      assertNotEquals(origHash, newHash)
    }
  }

  test("source generator is re-run when an output file is deleted") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, project, state) =>
      val generatorOutput = project.config.sourceGenerators
        .flatMap(_.headOption)
        .map(p => AbsolutePath(p.outputDirectory))
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = sourceHashFor("NameLengths_1.scala", project, compiledState1)
      assertExitStatus(compiledState1, ExitStatus.Ok)

      generatorOutput.foreach { out =>
        out.resolve("NameLengths_1.scala").toFile.delete()
      }

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = sourceHashFor("NameLengths_1.scala", project, compiledState2)
      assertNotEquals(origHash, newHash)
    }
  }

  test("source generator is not re-run when nothing has changed") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, project, state) =>
      val generatorOutput = project.config.sourceGenerators
        .flatMap(_.headOption)
        .map(p => AbsolutePath(p.outputDirectory))
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = sourceHashFor("NameLengths_1.scala", project, compiledState1)
      assertExitStatus(compiledState1, ExitStatus.Ok)

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = sourceHashFor("NameLengths_1.scala", project, compiledState2)
      assertEquals(origHash, newHash)
    }
  }

  private def assertNInputs(n: Int): String =
    s"""class Foo {
       |  val length = generated.NameLengths.args_${n}
       |  val test = generated.NameLengths.nameLengths
       |}""".stripMargin

  private def singleProjectWithSourceGenerator(includeGlobs: List[String])(
      op: (AbsolutePath, TestProject, TestState) => Unit
  ): Unit = TestUtil.withinWorkspace { workspace =>
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val sourceGenerator = Config.SourceGenerator(
      sourcesGlobs = List(Config.SourcesGlobs(workspace.underlying, None, includeGlobs, Nil)),
      outputDirectory = workspace.underlying.resolve("source-generator-output"),
      command = generator
    )
    val `A` = TestProject(workspace, "a", Nil, sourceGenerators = sourceGenerator :: Nil)
    val projects = List(`A`)
    val state = loadState(workspace, projects, logger)

    op(workspace, `A`, state)
  }

  private def sourceHashFor(name: String, project: TestProject, state: TestState): Option[Int] = {
    state.getLastResultFor(project) match {
      case Success(inputs, _, _, _, _, _, _) =>
        inputs.sources.collectFirst {
          case UniqueCompileInputs.HashedSource(source, hash) if source.name == name =>
            hash
        }
      case _ =>
        None
    }
  }
}
