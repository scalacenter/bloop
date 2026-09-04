package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

import bloop.cli.ExitStatus
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

import sbt.internal.inc.Analysis
import sbt.internal.inc.FileAnalysisStore

object PortableAnalysisSpec extends bloop.testing.BaseSuite {
  private val sourcesA = List(
    """/main/scala/A.scala
      |class A
      |""".stripMargin
  )
  private val sourcesB = List(
    """/main/scala/B.scala
      |class B extends A
      |""".stripMargin
  )
  private val bothCompiled =
    s"""Compiling a (1 Scala source)
       |Compiling b (1 Scala source)""".stripMargin

  private def withPortableAnalysis[T](enabled: Boolean)(op: => T): T = {
    val key = PortableAnalysis.EnabledProperty
    val previous = sys.props.get(key)
    sys.props.update(key, enabled.toString)
    try op
    finally previous.fold[Unit](sys.props.remove(key).foreach(_ => ()))(sys.props.update(key, _))
  }

  private def copyTree(from: Path, to: Path): Unit = {
    val stream = Files.walk(from)
    try {
      stream.forEach { source =>
        val target = to.resolve(from.relativize(source).toString)
        if (Files.isDirectory(source)) Files.createDirectories(target)
        else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        ()
      }
    } finally stream.close()
  }

  private def projectsIn(workspace: AbsolutePath): List[TestProject] = {
    val a = TestProject(workspace, "a", sourcesA)
    val b = TestProject(workspace, "b", sourcesB, List(a))
    List(a, b)
  }

  private def newLogger(): RecordingLogger = new RecordingLogger(ansiCodesSupported = false)

  private def compiled(logger: RecordingLogger): String =
    logger.compilingInfos.sorted.mkString(System.lineSeparator)

  private def sourceIdsOf(state: TestState, project: TestProject): Set[String] = {
    val file = state.getProjectFor(project).analysisOut
    val contents = FileAnalysisStore.binary(file.toFile).get.get
    contents.getAnalysis.asInstanceOf[Analysis].stamps.sources.keySet.map(_.id)
  }

  private final case class Relocated(
      workspace: AbsolutePath,
      state: TestState,
      projects: List[TestProject],
      logger: RecordingLogger
  )

  /**
   * Compiles `a` and `b` in one workspace, then recreates the same build in a second workspace,
   * ships the first workspace's outputs (analysis files and internal classes dirs) there, and
   * compiles again from a fresh state. `op` runs while the second workspace still exists.
   */
  private def withRelocatedBuild[T](op: Relocated => T): T = {
    TestUtil.withinWorkspace { origin =>
      val originProjects = projectsIn(origin)
      val originLogger = newLogger()
      val originState = loadState(origin, originProjects, originLogger)
      val originCompiled = originState.compile(originProjects.last)
      assertExitStatus(originCompiled, ExitStatus.Ok)
      assertValidCompilationState(originCompiled, originProjects)
      assertNoDiff(compiled(originLogger), bothCompiled)

      TestUtil.withinWorkspace { relocated =>
        val relocatedProjects = projectsIn(relocated)
        copyTree(origin.resolve("target").underlying, relocated.resolve("target").underlying)
        val logger = newLogger()
        val state = loadState(relocated, relocatedProjects, logger)
        val recompiled = state.compile(relocatedProjects.last)
        assertExitStatus(recompiled, ExitStatus.Ok)
        assertValidCompilationState(recompiled, relocatedProjects)
        op(Relocated(relocated, recompiled, relocatedProjects, logger))
      }
    }
  }

  /** Compiles in a workspace, then reloads the build from disk in the same workspace. */
  private def compileThenReload(writeEnabled: Boolean, readEnabled: Boolean): RecordingLogger = {
    TestUtil.withinWorkspace { workspace =>
      val projects = projectsIn(workspace)
      withPortableAnalysis(writeEnabled) {
        val first = loadState(workspace, projects, newLogger()).compile(projects.last)
        assertExitStatus(first, ExitStatus.Ok)
      }
      withPortableAnalysis(readEnabled) {
        val logger = newLogger()
        val reloaded = loadState(workspace, projects, logger).compile(projects.last)
        assertExitStatus(reloaded, ExitStatus.Ok)
        assertValidCompilationState(reloaded, projects)
        logger
      }
    }
  }

  test("relocated workspace compiles as a no-op when portable analysis is on") {
    withPortableAnalysis(enabled = true) {
      withRelocatedBuild { build =>
        assertNoDiff(compiled(build.logger), "")
        assert(!build.logger.debugs.exists(_.contains("Classpath hash changed")))
      }
    }
  }

  test("relocated workspace recompiles when portable analysis is off") {
    withPortableAnalysis(enabled = false) {
      withRelocatedBuild(build => assertNoDiff(compiled(build.logger), bothCompiled))
    }
  }

  test("relocated workspace stays incremental") {
    withPortableAnalysis(enabled = true) {
      withRelocatedBuild { build =>
        assertNoDiff(compiled(build.logger), "")
        val source = build.projects.head.srcFor("/main/scala/A.scala")
        Files.write(source.underlying, "class A // touched".getBytes(StandardCharsets.UTF_8))
        val logger = newLogger()
        val state = build.state.withLogger(logger).compile(build.projects.last)
        assertExitStatus(state, ExitStatus.Ok)
        assertNoDiff(compiled(logger), "Compiling a (1 Scala source)")
      }
    }
  }

  test("an analysis written with absolute paths loads when portable analysis is on") {
    assertNoDiff(compiled(compileThenReload(writeEnabled = false, readEnabled = true)), "")
  }

  test("a portable analysis loads on the same machine when portable analysis is off") {
    assertNoDiff(compiled(compileThenReload(writeEnabled = true, readEnabled = false)), "")
  }

  test("an analysis with an unknown root is ignored with a warning and the build keeps working") {
    withPortableAnalysis(enabled = true) {
      withRelocatedBuild { build =>
        val a = build.projects.head
        val project = build.state.getProjectFor(a)
        val analysisFile = project.analysisOut.toFile
        // Rewrite a's analysis with a root this machine will not recognise
        val known = PortableAnalysis.readMappers(
          PortableAnalysis.Roots.derive(project.workspaceRoot.underlying)
        )
        val contents = FileAnalysisStore.binary(analysisFile, known.mappers).get.get
        val foreign = PortableAnalysis.Roots(List("NOPE" -> build.workspace.underlying))
        FileAnalysisStore.binary(analysisFile, PortableAnalysis.writeMappers(foreign)).set(contents)

        val logger = newLogger()
        val reloaded = loadState(build.workspace, build.projects, logger)
        assert(
          logger.warnings.exists(w =>
            w.contains("Ignoring persisted analysis for 'a'") && w.contains("NOPE")
          )
        )
        val recompiled = reloaded.compile(build.projects.last)
        assertExitStatus(recompiled, ExitStatus.Ok)
        assert(logger.compilingInfos.contains("Compiling a (1 Scala source)"))

        val again = newLogger()
        val noop = recompiled.withLogger(again).compile(build.projects.last)
        assertExitStatus(noop, ExitStatus.Ok)
        assertNoDiff(compiled(again), "")
      }
    }
  }

  test("persisted paths stay absolute unless portable analysis is on") {
    TestUtil.withinWorkspace { workspace =>
      val projects = projectsIn(workspace)
      val off = withPortableAnalysis(enabled = false) {
        loadState(workspace, projects, newLogger()).compile(projects.last)
      }
      assertExitStatus(off, ExitStatus.Ok)
      val absoluteIds = sourceIdsOf(off, projects.head)
      assert(absoluteIds.nonEmpty && absoluteIds.forall(id => Paths.get(id).isAbsolute))

      // A no-op compile re-persists a missing analysis file with the current setting
      val on = withPortableAnalysis(enabled = true) {
        Files.delete(off.getProjectFor(projects.head).analysisOut.underlying)
        off.compile(projects.last)
      }
      assertExitStatus(on, ExitStatus.Ok)
      val tokenIds = sourceIdsOf(on, projects.head)
      assert(tokenIds.nonEmpty && tokenIds.forall(_.startsWith("${BASE}/")))
    }
  }
}
