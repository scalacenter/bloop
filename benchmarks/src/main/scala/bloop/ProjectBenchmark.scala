package bloop

import java.nio.file.Files

import bloop.io.AbsolutePath
import bloop.logging.NoOpLogger
import org.openjdk.jmh.annotations.Benchmark

object ProjectBenchmark {
  val projects = bloop.benchmarks.BuildInfo.test_resourceDirectory.toPath.resolve("projects")
  def getProjectBase(name: String) = AbsolutePath(projects.resolve(name))
  def existing(path: AbsolutePath): AbsolutePath = {
    assert(Files.exists(path.underlying))
    path
  }
  val sbtLocation = existing(getProjectBase("sbt"))
  val sbtRootProjectLocation = existing(
    sbtLocation.resolve("bloop-config").resolve("sbtRoot.config"))
  val uTestLocation = existing(getProjectBase("utest"))
}

class ProjectBenchmark {

  @Benchmark
  def loadSbtProject(): Unit = {
    val _ = Project.fromDir(ProjectBenchmark.sbtLocation, NoOpLogger)
  }

  @Benchmark
  def loadUTestProject(): Unit = {
    val _ = Project.fromDir(ProjectBenchmark.uTestLocation, NoOpLogger)
  }

  @Benchmark
  def loadSbtRootProject(): Unit = {
    val _ = Project.fromFile(ProjectBenchmark.sbtRootProjectLocation, NoOpLogger)
  }

}
