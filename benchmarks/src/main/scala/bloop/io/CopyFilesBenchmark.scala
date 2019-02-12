package bloop.io

import java.nio.file.{Files, Path, Paths => NioPaths}
import java.util.concurrent.TimeUnit

import bloop.engine.ExecutionContext
import monix.execution.Scheduler
import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations.Mode.SingleShotTime
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

@State(Scope.Benchmark)
abstract class CopyFilesBenchmarkBase {
  var origin: Path = null
  var target: Path = null
  var scheduler: Scheduler = null

  @Setup(Level.Trial) def setUp(): Unit = {
    scheduler = ExecutionContext.ioScheduler
    /*origin = NioPaths.get("/Users/jvican/Code/bloop/.bloop/frontend/scala-2.12/classes")
    target = NioPaths.get("/Users/jvican/Code/bloop/.bloop/frontend/scala-2.12/classes-2")*/
    origin = NioPaths.get("/Users/jvican/Code/bloop/.bloop/frontend/scala-2.12/bloop-classes.jar")
    target = NioPaths.get("/Users/jvican/Code/bloop/.bloop/frontend/scala-2.12/classes-2")
    if (Files.exists(target)) Paths.delete(AbsolutePath(target))
  }

  def copyIvyCache(): Unit

  @TearDown(Level.Invocation) def tearDown(): Unit = {
    Paths.delete(AbsolutePath(target))
  }
}

@Fork(value = 3)
@State(Scope.Benchmark)
@BenchmarkMode(Array(SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ParallelCopyFilesBenchmark extends CopyFilesBenchmarkBase {
  @Benchmark
  def copyIvyCache(): Unit = {
    /*val task = Paths.copyDirectoriesAsync(AbsolutePath(origin), AbsolutePath(target), 1)
    //val task = Paths.copyDirectories(AbsolutePath(origin), AbsolutePath(target), 5)(scheduler)
    val future = task.runAsync(scheduler)
    Await.result(future, FiniteDuration(10, TimeUnit.SECONDS))*/
    //Paths.copyDirectoriesSequentially(AbsolutePath(origin), AbsolutePath(target))
    sbt.io.IO.unzip(origin.toFile, target.toFile)
    ()
  }
}
