package bloop.tasks

import bloop.Compiler
import bloop.cli.{Commands, ExitStatus}
import bloop.data.Project
import bloop.engine.{Build, Run, State}
import bloop.engine.caches.ResultsCache
import bloop.exec.JavaEnv
import bloop.io.AbsolutePath
import bloop.logging.BloopLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class CleanTaskSpec {

  private def dummyProject(
    buildPath: AbsolutePath,
    name: String,
    dependencies: Set[String]
  ): Project =
    TestUtil.makeProject(
      buildPath.underlying,
      name,
      sources = Map.empty,
      dependencies = dependencies,
      scalaInstance = None,
      javaEnv = JavaEnv.default
    )

  /**
   * Executes the given `command` and checks if it cleaned `expected` projects.
   *
   * @param projectsWithDeps The projects contained in this build along with their dependencies.
   * @param command          The command to execute.
   * @param expectedProjects The projects expected to be cleaned by `command`.
   * @param expectedStatus   The exit status expected to be set by `command`.
   */
  private def cleanAndCheck(
    projectsWithDeps: Set[(String, Set[String])],
    command: Commands.Clean,
    expectedProjects: Set[String],
    expectedStatus: ExitStatus = ExitStatus.Ok
  ): Unit = {
    TestUtil.withTemporaryDirectory { temp =>
      val buildPath = AbsolutePath(temp)
      val logger = new bloop.logging.RecordingLogger
      val projects = projectsWithDeps.map {
        case (project, deps) => dummyProject(buildPath, project, deps)
      }
      val build = Build(buildPath, projects.toList)
      val results = projects.foldLeft(ResultsCache.forTests) { (cache, project) =>
        cache.addResult(project, Compiler.Result.Empty)
      }
      val state = State.forTests(build, CompilationHelpers.getCompilerCache(logger), logger)
        .copy(results = results)

      val cleanState = TestUtil.blockingExecute(Run(command), state)

      val projectsLeft = cleanState.results.allSuccessful.map {
        case (project, _) => project.name
      }.toSet
      val projectsCleaned = projectsWithDeps.map(_._1).toSet -- projectsLeft
      assertEquals(expectedProjects, projectsCleaned)
      assertEquals(expectedStatus, cleanState.status)
    }
  }

  @Test
  def cleansSingleProject: Unit =
    cleanAndCheck(
      Set(
        "foo" -> Set("bar"),
        "bar" -> Set.empty,
        "baz" -> Set.empty
      ),
      Commands.Clean(List("foo")),
      Set("foo")
    )

  @Test
  def cleansMultipleProjects: Unit =
    cleanAndCheck(
      Set(
        "foo" -> Set.empty,
        "bar" -> Set.empty,
        "baz" -> Set.empty
      ),
      Commands.Clean(List("foo", "bar")),
      Set("foo", "bar")
    )

  @Test
  def cleansDependentProjects: Unit =
    cleanAndCheck(
      Set(
        "foo" -> Set("bar"),
        "bar" -> Set.empty,
        "baz" -> Set.empty
      ),
      Commands.Clean(List("foo"), includeDependencies = true),
      Set("foo", "bar")
    )

  @Test
  def cleansAllProjectsByDefault: Unit =
    cleanAndCheck(
      Set(
        "foo" -> Set.empty,
        "bar" -> Set.empty
      ),
      Commands.Clean(Nil),
      Set("foo", "bar")
    )

  @Test
  def errorsOnMissingProjects: Unit =
    cleanAndCheck(
      Set(
        "foo" -> Set("bar"),
        "bar" -> Set.empty,
      ),
      Commands.Clean(List("baz")),
      Set(),
      ExitStatus.merge(ExitStatus.UnexpectedError, ExitStatus.InvalidCommandLineOption)
    )

}
