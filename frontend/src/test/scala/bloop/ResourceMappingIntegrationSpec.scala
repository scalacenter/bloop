package bloop

import java.nio.file.Files
import bloop.cli.ExitStatus
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil
import bloop.data.LoadedProject

object ResourceMappingIntegrationSpec extends bloop.testing.BaseSuite {

  test("compile with resource mappings copies files to classes directory") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)

      // Create a simple project
      val source =
        """/main/scala/Foo.scala
          |class Foo
        """.stripMargin

      val `A` = TestProject(workspace, "a", List(source))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      // Create a resource file to map
      val resourceFile = workspace.resolve("custom-resource.txt")
      Files.write(resourceFile.underlying, "resource content".getBytes("UTF-8"))

      // Get the project and inject mappings manually
      val projectA = state.getProjectFor(`A`)
      val mappings = List((resourceFile, "assets/config.txt"))
      val projectWithMappings = projectA.copy(resourceMappings = mappings)

      // Update state with modified project
      val stateWithMappings = new TestState(
        state.state.copy(
          build = state.state.build.copy(
            loadedProjects = state.state.build.loadedProjects.map { lp =>
              if (lp.project.name == projectA.name) {
                lp match {
                  case LoadedProject.RawProject(_) => LoadedProject.RawProject(projectWithMappings)
                  case LoadedProject.ConfiguredProject(_, original, settings) =>
                    LoadedProject.ConfiguredProject(projectWithMappings, original, settings)
                }
              } else lp
            }
          )
        )
      )

      // Compile
      val compiledState = stateWithMappings.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      // Verify resource was copied to classes directory
      val classesDir = compiledState.getClientExternalDir(`A`)
      val copiedResource = classesDir.resolve("assets/config.txt")

      try {
        assert(copiedResource.exists)
        val content = new String(Files.readAllBytes(copiedResource.underlying), "UTF-8")
        assertNoDiff(content, "resource content")
      } catch {
        case t: Throwable =>
          logger.dump()
          throw t
      }
    }
  }

  test("compile with directory mapping copies entire directory") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)

      val `A` = TestProject(workspace, "a", List("/main/scala/Foo.scala\nclass Foo"))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      // Create a directory structure to map
      val resourceDir = workspace.resolve("resources")
      Files.createDirectories(resourceDir.underlying)
      Files.write(resourceDir.resolve("file1.txt").underlying, "content 1".getBytes("UTF-8"))
      val subDir = resourceDir.resolve("sub")
      Files.createDirectories(subDir.underlying)
      Files.write(subDir.resolve("file2.txt").underlying, "content 2".getBytes("UTF-8"))

      // Inject mappings
      val projectA = state.getProjectFor(`A`)
      val mappings = List((resourceDir, "data"))
      val projectWithMappings = projectA.copy(resourceMappings = mappings)

      val stateWithMappings = new TestState(
        state.state.copy(
          build = state.state.build.copy(
            loadedProjects = state.state.build.loadedProjects.map { lp =>
              if (lp.project.name == projectA.name) {
                lp match {
                  case LoadedProject.RawProject(_) => LoadedProject.RawProject(projectWithMappings)
                  case LoadedProject.ConfiguredProject(_, original, settings) =>
                    LoadedProject.ConfiguredProject(projectWithMappings, original, settings)
                }
              } else lp
            }
          )
        )
      )

      // Compile
      val compiledState = stateWithMappings.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      // Verify directory structure copied
      val classesDir = compiledState.getClientExternalDir(`A`)
      assert(classesDir.resolve("data/file1.txt").exists)
      assert(classesDir.resolve("data/sub/file2.txt").exists)
    }
  }

  test("resources are copied even if compilation is no-op") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)

      val `A` = TestProject(workspace, "a", List("/main/scala/Foo.scala\nclass Foo"))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)

      val resourceFile = workspace.resolve("res.txt")
      Files.write(resourceFile.underlying, "v1".getBytes("UTF-8"))

      val projectA = state.getProjectFor(`A`)
      val mappings = List((resourceFile, "res.txt"))
      val projectWithMappings = projectA.copy(resourceMappings = mappings)

      val stateWithMappings = new TestState(
        state.state.copy(
          build = state.state.build.copy(
            loadedProjects = state.state.build.loadedProjects.map { lp =>
              if (lp.project.name == projectA.name) {
                lp match {
                  case LoadedProject.RawProject(_) => LoadedProject.RawProject(projectWithMappings)
                  case LoadedProject.ConfiguredProject(_, original, settings) =>
                    LoadedProject.ConfiguredProject(projectWithMappings, original, settings)
                }
              } else lp
            }
          )
        )
      )

      // First compile
      val compiledState = stateWithMappings.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      // Verify v1
      val classesDir = compiledState.getClientExternalDir(`A`)
      val copiedFile = classesDir.resolve("res.txt")
      assertNoDiff(new String(Files.readAllBytes(copiedFile.underlying)), "v1")

      // Update resource
      Files.write(resourceFile.underlying, "v2".getBytes("UTF-8"))

      // Compile again (should be no-op for scala, but resources should update)
      val compiledState2 = compiledState.compile(`A`)
      assertExitStatus(compiledState2, ExitStatus.Ok)

      // Verify v2
      assertNoDiff(new String(Files.readAllBytes(copiedFile.underlying)), "v2")
    }
  }
}
