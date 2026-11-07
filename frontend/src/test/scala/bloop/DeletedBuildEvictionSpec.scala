package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

  test("never removes an existing build while other threads update the cache") {
    TestUtil.withinWorkspace { workspace =>
      val logger = newLogger()
      val cache = StateCache.empty
      val existing = (1 to 4).map(i => loadBuildIn(workspace.resolve(s"e$i"), logger).state)
      val deleted = (1 to 4).map(i => loadBuildIn(workspace.resolve(s"d$i"), logger).state)
      deleted.foreach(state => BloopPaths.delete(state.build.origin))
      val all = existing ++ deleted
      val existingDirs = existing.map(_.build.origin).toSet

      val isRunning = new AtomicBoolean(true)
      val writers = (1 to 4).map { seed =>
        val random = new scala.util.Random(seed.toLong)
        val thread = new Thread(() => {
          while (isRunning.get()) cache.updateBuild(all(random.nextInt(all.size)))
        })
        thread.start()
        thread
      }

      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
      var removedExisting = Set.empty[AbsolutePath]
      while (System.nanoTime() < deadline) {
        val removed = cache.removeDeletedBuilds().map(_.origin).toSet
        removedExisting ++= removed.intersect(existingDirs)
      }
      isRunning.set(false)
      writers.foreach(_.join())

      cache.removeDeletedBuilds()
      assertEquals(removedExisting, Set.empty[AbsolutePath])
      existing.foreach(state => assert(cache.getRawCachedBuildFor(state.build.origin).isDefined))
      deleted.foreach(state => assert(cache.getRawCachedBuildFor(state.build.origin).isEmpty))
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
