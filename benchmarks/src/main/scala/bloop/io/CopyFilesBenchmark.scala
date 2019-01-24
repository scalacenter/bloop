package bloop.io

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import bloop.engine.ExecutionContext
import monix.execution.Scheduler
import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
abstract class CopyFilesBenchmarkBase {
  var origin: Path = null
  var target: Path = null
  var scheduler: Scheduler = null

  @Setup(Level.Trial) def setUp(): Unit = {
    scheduler = ExecutionContext.ioScheduler
    origin = AbsolutePath(System.getProperty("user.home")).resolve(".ivy2").underlying
    target = Files.createTempDirectory("copy-parallel-benchmark")
  }

  def copyIvyCache(): Unit

  @TearDown(Level.Trial) def tearDown(): Unit = {
    Paths.delete(AbsolutePath(target))
  }
}

@Fork(value = 3)
@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
class ParallelCopyFilesBenchmark extends CopyFilesBenchmarkBase {
  @Benchmark
  def copyIvyCache(): Unit = {
    Paths.copyDirectoriesAsync(AbsolutePath(origin), AbsolutePath(target))(scheduler)
  }
}
