package bloop

import java.nio.file.Files

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.internal.build.BuildTestInfo
import bloop.io.AbsolutePath
import bloop.io.ByteHasher
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

object SourceGeneratorSpec extends bloop.testing.BaseSuite {

  val generator = TestUtil.generator

  lazy val hasPython = generator.nonEmpty

  def testOnlyWithPython(name: String)(fun: => Any): Unit = {
    if (hasPython) test(name)(fun)
    else ignore(name, label = s"IGNORED: no python found on path")(fun)
  }

  def getHashedSource(workspace: AbsolutePath, name: String): Int = {
    val source = workspace.resolve(name)
    val content = Files.readAllBytes(source.underlying)
    ByteHasher.hashBytes(content)
  }

  testOnlyWithPython("compile a project having a source generator") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, _, project, state) =>
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

  testOnlyWithPython("compile projects with dependent source generators") {
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

  testOnlyWithPython("source generator failure yields compilation failure") {
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

  testOnlyWithPython("source generator is run when there are no matching input files") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (_, _, project, state) =>
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 0))
      val compiledState = state.compile(project)
      assertExitStatus(compiledState, ExitStatus.Ok)
    }
  }

  testOnlyWithPython("source generator is re-run when an input file is removed") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, _, project, state) =>
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

  testOnlyWithPython("source generator is re-run when an input file is added") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, _, project, state) =>
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

  testOnlyWithPython("source generator is re-run when an input file is modified") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, output, project, state) =>
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = getHashedSource(output, "NameLengths_1.scala")

      assertExitStatus(compiledState1, ExitStatus.Ok)

      writeFile(workspace.resolve("hello.in"), "goodbye")

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = getHashedSource(output, "NameLengths_1.scala")
      assertNotEquals(origHash, newHash)
    }
  }

  testOnlyWithPython("source generator is re-run when an output file is modified") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, output, project, state) =>
      val generatorOutput = project.config.sourceGenerators
        .flatMap(_.headOption)
        .map(p => AbsolutePath(p.outputDirectory))
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = getHashedSource(output, "NameLengths_1.scala")
      assertExitStatus(compiledState1, ExitStatus.Ok)

      generatorOutput.foreach { out =>
        writeFile(out.resolve("NameLengths_1.scala"), "won't compile -- must rerun generator.")
      }

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = getHashedSource(output, "NameLengths_1.scala")
      assertNotEquals(origHash, newHash)
    }
  }

  testOnlyWithPython("source generator is re-run when generator file is modified") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, output, project, state) =>
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = getHashedSource(output, "NameLengths_1.scala")
      assertExitStatus(compiledState1, ExitStatus.Ok)

      val generatorFile = AbsolutePath(BuildTestInfo.sampleSourceGenerator.toPath())
      writeFile(
        generatorFile,
        readFile(generatorFile) +
          """|
             |def random():
             |    return 123
             |
             |""".stripMargin
      )
      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = getHashedSource(output, "NameLengths_1.scala")
      assertNotEquals(origHash, newHash)
    }
  }

  testOnlyWithPython("source generator is re-run when an output file is deleted") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, output, project, state) =>
      val generatorOutput = project.config.sourceGenerators
        .flatMap(_.headOption)
        .map(p => AbsolutePath(p.outputDirectory))
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = getHashedSource(output, "NameLengths_1.scala")
      assertExitStatus(compiledState1, ExitStatus.Ok)

      generatorOutput.foreach { out =>
        out.resolve("NameLengths_1.scala").toFile.delete()
      }

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = getHashedSource(output, "NameLengths_1.scala")
      assertNotEquals(origHash, newHash)
    }
  }

  testOnlyWithPython("source generator is not re-run when nothing has changed") {
    singleProjectWithSourceGenerator("glob:*.in" :: Nil) { (workspace, output, project, state) =>
      writeFile(workspace.resolve("hello.in"), "hello")
      writeFile(project.srcFor("test.scala", exists = false), assertNInputs(n = 1))
      val compiledState1 = state.compile(project)
      val origHash = getHashedSource(output, "NameLengths_1.scala")
      assertExitStatus(compiledState1, ExitStatus.Ok)

      val compiledState2 = compiledState1.compile(project)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      val newHash = getHashedSource(output, "NameLengths_1.scala")
      assertEquals(origHash, newHash)
    }
  }

  private def assertNInputs(n: Int): String =
    s"""class Foo {
       |  val length = generated.NameLengths.args_${n}
       |  val test = generated.NameLengths.nameLengths
       |}""".stripMargin

  private def singleProjectWithSourceGenerator(includeGlobs: List[String])(
      op: (AbsolutePath, AbsolutePath, TestProject, TestState) => Unit
  ): Unit = TestUtil.withinWorkspace { workspace =>
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val output = workspace.resolve("source-generator-output")
    val sourceGenerator = Config.SourceGenerator(
      sourcesGlobs = List(Config.SourcesGlobs(workspace.underlying, None, includeGlobs, Nil)),
      outputDirectory = output.underlying,
      command = generator,
      unmanagedInputs = List(BuildTestInfo.sampleSourceGenerator.toPath())
    )
    val `A` = TestProject(workspace, "a", Nil, sourceGenerators = sourceGenerator :: Nil)
    val projects = List(`A`)
    val state = loadState(workspace, projects, logger)

    op(workspace, output, `A`, state)
  }

}
