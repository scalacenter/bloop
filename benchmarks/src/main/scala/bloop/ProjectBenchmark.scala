package bloop

import java.nio.file.Files

import bloop.io.AbsolutePath
import bloop.logging.NoopLogger
import bloop.tasks.ProjectHelpers
import org.openjdk.jmh.annotations.Benchmark

object ProjectBenchmark {
  val sbtLocation = existing(AbsolutePath(ProjectHelpers.getBloopConfigDir("sbt").getParent))
  val sbtRootProjectLocation = existing(
    AbsolutePath(ProjectHelpers.getBloopConfigDir("sbt").resolve("sbtRoot.config")))
  val uTestLocation = existing(AbsolutePath(ProjectHelpers.getBloopConfigDir("utest").getParent))

  def existing(path: AbsolutePath): AbsolutePath = {
    assert(Files.exists(path.underlying))
    path
  }
}

class ProjectBenchmark {

  @Benchmark
  def loadSbtProject(): Unit = {
    val _ = Project.fromDir(ProjectBenchmark.sbtLocation, NoopLogger)
  }

  @Benchmark
  def loadUTestProject(): Unit = {
    val _ = Project.fromDir(ProjectBenchmark.uTestLocation, NoopLogger)
  }

  @Benchmark
  def loadSbtRootProject(): Unit = {
    val _ = Project.fromFile(ProjectBenchmark.sbtRootProjectLocation, NoopLogger)
  }

}
