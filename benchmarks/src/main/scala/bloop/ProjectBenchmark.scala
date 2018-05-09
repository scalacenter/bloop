package bloop

import java.nio.file.Files

import bloop.io.AbsolutePath
import bloop.logging.NoopLogger
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil
import org.openjdk.jmh.annotations.Benchmark
import java.util.concurrent.TimeUnit

import bloop.engine.Build

import scala.concurrent.duration.FiniteDuration

object ProjectBenchmark {
  val sbt = existing(AbsolutePath(TestUtil.getBloopConfigDir("sbt")))
  val lichess = existing(AbsolutePath(TestUtil.getBloopConfigDir("lichess")))
  val akka = existing(AbsolutePath(TestUtil.getBloopConfigDir("akka")))

  def existing(path: AbsolutePath): AbsolutePath = {
    assert(Files.exists(path.underlying))
    path
  }
}

class ProjectBenchmark {
  final def loadProject(configDir: AbsolutePath): Unit = {
    val t = Project
      .lazyLoadFromDir(configDir, NoopLogger)
      .map(ps => Build(configDir, ps))
    TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS))(t)
    ()
  }

  @Benchmark
  def loadSbtProject(): Unit = {
    loadProject(ProjectBenchmark.sbt)
  }

  @Benchmark
  def loadLichessProject(): Unit = {
    loadProject(ProjectBenchmark.lichess)
  }

  @Benchmark
  def loadAkkaProject(): Unit = {
    loadProject(ProjectBenchmark.akka)
  }
}
