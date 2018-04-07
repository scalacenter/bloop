package bloop

import java.nio.file.Files

import bloop.io.AbsolutePath
import bloop.logging.NoopLogger
import bloop.tasks.TestUtil
import org.openjdk.jmh.annotations.Benchmark

object ProjectBenchmark {
  val sbtLocation = existing(AbsolutePath(TestUtil.getBloopConfigDir("sbt").getParent))
  val sbtRootProjectLocation = existing(
    AbsolutePath(TestUtil.getBloopConfigDir("sbt").resolve("sbtRoot.config")))
  val uTestLocation = existing(AbsolutePath(TestUtil.getBloopConfigDir("utest").getParent))

  def existing(path: AbsolutePath): AbsolutePath = {
    assert(Files.exists(path.underlying))
    path
  }
}

class ProjectBenchmark {

  @Benchmark
  def loadSbtProject(): Unit = {
    val _ = Project.eagerLoadFromDir(ProjectBenchmark.sbtLocation, NoopLogger)
  }

  @Benchmark
  def loadUTestProject(): Unit = {
    val _ = Project.eagerLoadFromDir(ProjectBenchmark.uTestLocation, NoopLogger)
  }

  @Benchmark
  def loadSbtRootProject(): Unit = {
    val _ = Project.eagerLoadFromDir(ProjectBenchmark.sbtRootProjectLocation, NoopLogger)
  }

}
