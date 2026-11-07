package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

import bloop.cli.ExitStatus
import bloop.engine.State
import bloop.engine.caches.StateCache
import bloop.engine.tasks.compilation.CompileGatekeeper
import bloop.io.AbsolutePath
import bloop.io.{Paths => BloopPaths}
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

object DeletedBuildEvictionSpec extends bloop.testing.BaseSuite {
  private val sources = List(
    """/main/scala/A.scala
      |object A { def a: Int = B.b }
      """.stripMargin,
    """/main/scala/B.scala
      |object B { def b: Int = 1 }
      """.stripMargin
  )

  private def newLogger() = new RecordingLogger(ansiCodesSupported = false)

  private def loadBuildIn(dir: AbsolutePath, logger: RecordingLogger): TestState = {
    Files.createDirectories(dir.underlying)
    loadState(dir, List(TestProject(dir, "a", sources)), logger)
  }

  test("removes builds whose config dir or project config files are gone") {
    TestUtil.withinWorkspace { workspace =>
      val logger = newLogger()
      val cache = StateCache.empty
      def cachedConfigDir(name: String): AbsolutePath = {
        val state = loadBuildIn(workspace.resolve(name), logger)
        cache.updateBuild(state.state)
        state.build.origin
      }

      val kept = cachedConfigDir("kept")
      val deletedDir = cachedConfigDir("deleted-dir")
      val deletedConfigs = cachedConfigDir("deleted-configs")
      BloopPaths.delete(deletedDir)
      Files.list(deletedConfigs.underlying).iterator().asScala.foreach { path =>
        if (path.toString.endsWith(".json")) Files.delete(path)
      }

      val removed = cache.removeDeletedBuilds().map(_.origin).toSet
      assertEquals(removed, Set(deletedDir, deletedConfigs))
      assert(cache.getRawCachedBuildFor(kept).isDefined)
      assert(cache.getRawCachedBuildFor(deletedDir).isEmpty)
      assert(cache.getRawCachedBuildFor(deletedConfigs).isEmpty)
      assertEquals(cache.removeDeletedBuilds(), Nil)
    }
  }

  test("keeps a build that was replaced after it was selected for removal") {
    TestUtil.withinWorkspace { workspace =>
      val logger = newLogger()
      val cache = StateCache.empty
      val selected = loadBuildIn(workspace.resolve("w"), logger).state
      val replacement = loadBuildIn(workspace.resolve("w"), logger).state
      cache.updateBuild(selected)

      val removed = cache.removeBuildsWhere { _ =>
        cache.updateBuild(replacement)
        true
      }

      assertEquals(removed, Nil)
      assert(cache.getRawCachedBuildFor(replacement.build.origin).exists(_ eq replacement.build))
    }
  }

  test("evicting a deleted build drops its last successful results") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "a", sources)
      val compiled = loadState(workspace, List(`A`), newLogger()).compile(`A`)
      assertExitStatus(compiled, ExitStatus.Ok)
      State.stateCache.updateBuild(compiled.state)
      val project = compiled.getProjectFor(`A`)
      val configDir = compiled.build.origin
      assert(CompileGatekeeper.latestSuccessfulResult(project).isDefined)

      BloopPaths.delete(configDir)
      assert(State.evictDeletedBuilds().contains(configDir))
      assert(State.stateCache.getRawCachedBuildFor(configDir).isEmpty)
      assert(CompileGatekeeper.latestSuccessfulResult(project).isEmpty)
    }
  }

  test("a build evicted while its config dir was missing reloads incrementally") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "a", sources)
      val compiled = loadState(workspace, List(`A`), newLogger()).compile(`A`)
      assertExitStatus(compiled, ExitStatus.Ok)
      State.stateCache.updateBuild(compiled.state)
      val configDir = compiled.build.origin

      val awayDir = workspace.resolve(".bloop-away")
      Files.move(configDir.underlying, awayDir.underlying)
      assert(State.evictDeletedBuilds().contains(configDir))
      Files.move(awayDir.underlying, configDir.underlying)

      val logger = newLogger()
      val reloaded = TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS)) {
        val state = compiled.state
        State.loadActiveStateFor(configDir, state.client, state.pool, state.commonOptions, logger)
      }
      val source = `A`.srcFor("/main/scala/B.scala")
      Files.write(source.underlying, "object B { def b: Int = 2 }".getBytes(StandardCharsets.UTF_8))
      val recompiled = new TestState(reloaded).compile(`A`)
      assertExitStatus(recompiled, ExitStatus.Ok)
      assertEquals(logger.compilingInfos, List("Compiling a (1 Scala source)"))
    }
  }
}
