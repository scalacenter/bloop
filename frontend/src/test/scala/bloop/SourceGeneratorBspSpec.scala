package bloop

import ch.epfl.scala.bsp.SourceItem
import ch.epfl.scala.bsp.SourceItemKind
import ch.epfl.scala.bsp.SourcesItem
import ch.epfl.scala.bsp.Uri

import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.config.Config
import bloop.internal.build.BuildTestInfo
import bloop.logging.RecordingLogger
import bloop.util.CrossPlatform
import bloop.util.TestProject
import bloop.util.TestUtil

object TcpBspSourceGeneratorSpec extends BspSourceGeneratorSpec(BspProtocol.Tcp)
object LocalBspSourceGeneratorSpec extends BspSourceGeneratorSpec(BspProtocol.Local)

abstract class BspSourceGeneratorSpec(override val protocol: BspProtocol) extends BspBaseSuite {
  private val generator =
    if (CrossPlatform.isWindows) List("python", BuildTestInfo.sampleSourceGenerator.getAbsolutePath)
    else List(BuildTestInfo.sampleSourceGenerator.getAbsolutePath)

  test("sources request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val output = workspace.resolve("source-generator-output")
      val sourceGenerator = Config.SourceGenerator(
        sourcesGlobs = Nil,
        outputDirectory = output.underlying,
        command = generator
      )
      val `A` = TestProject(workspace, "a", Nil, sourceGenerators = sourceGenerator :: Nil)

      loadBspState(workspace, `A` :: Nil, logger) { state =>
        val obtained = state.requestSources(`A`).items
        val expected = SourcesItem(
          `A`.bspId,
          List(
            SourceItem(
              Uri(workspace.resolve("a").resolve("src").toBspUri),
              SourceItemKind.Directory,
              generated = false
            ),
            SourceItem(
              Uri(output.resolve("NameLengths_0.scala").toBspUri),
              SourceItemKind.File,
              generated = true
            )
          ),
          None
        )
        assertEquals(obtained, expected :: Nil)
      }
    }
  }

  test("sources request with dependent source generators works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)

      val outputA = workspace.resolve("source-generator-output-A")
      val sourceGeneratorA = Config.SourceGenerator(
        sourcesGlobs = List(
          Config.SourcesGlobs(workspace.underlying, None, "glob:*.test_input" :: Nil, Nil)
        ),
        outputDirectory = outputA.underlying,
        command = generator
      )
      val `A` = TestProject(workspace, "a", Nil, sourceGenerators = sourceGeneratorA :: Nil)

      // The inputs of the source generator of project A
      List(
        "file.test_input",
        "file_two.test_input",
        "excluded_file.whatever"
      ).foreach { file =>
        writeFile(workspace.resolve(file), "")
      }

      val outputB = workspace.resolve("source-generator-output-B")
      val sourceGeneratorB = Config.SourceGenerator(
        sourcesGlobs = List(
          Config.SourcesGlobs(outputA.underlying, None, "glob:*.scala" :: Nil, Nil)
        ),
        outputDirectory = outputB.underlying,
        command = generator
      )
      val `B` = TestProject(
        workspace,
        "b",
        Nil,
        directDependencies = `A` :: Nil,
        sourceGenerators = sourceGeneratorB :: Nil
      )

      loadBspState(workspace, `A` :: `B` :: Nil, logger) { state =>
        val obtainedA = state.requestSources(`A`).items
        val expectedA = SourcesItem(
          `A`.bspId,
          List(
            SourceItem(
              Uri(workspace.resolve("a").resolve("src").toBspUri),
              SourceItemKind.Directory,
              generated = false
            ),
            SourceItem(
              Uri(outputA.resolve("NameLengths_2.scala").toBspUri),
              SourceItemKind.File,
              generated = true
            )
          ),
          None
        )
        assertEquals(obtainedA, expectedA :: Nil)

        val obtainedB = state.requestSources(`B`).items
        val expectedB = SourcesItem(
          `B`.bspId,
          List(
            SourceItem(
              Uri(workspace.resolve("b").resolve("src").toBspUri),
              SourceItemKind.Directory,
              generated = false
            ),
            SourceItem(
              Uri(outputB.resolve("NameLengths_1.scala").toBspUri),
              SourceItemKind.File,
              generated = true
            )
          ),
          None
        )
        assertEquals(obtainedB, expectedB :: Nil)
      }
    }
  }
}
