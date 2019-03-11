package bloop

import java.nio.file.Files

import bloop.data.ClientInfo
import bloop.io.AbsolutePath
import bloop.logging.NoopLogger
import bloop.util.TestUtil
import org.openjdk.jmh.annotations.Benchmark
import java.util.concurrent.TimeUnit

import bloop.cli.CommonOptions
import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._
import bloop.engine.NoPool

import scala.concurrent.duration.FiniteDuration

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
class ProjectBenchmark {
  final def loadProject(configDir: AbsolutePath): Unit = {
    import bloop.engine.State
    val client = ClientInfo.CliClientInfo("bloop-cli")
    val t = State.loadActiveStateFor(configDir, client, NoPool, CommonOptions.default, NoopLogger)
    TestUtil.await(FiniteDuration(10, TimeUnit.SECONDS))(t)
    ()
  }

  private var sbt: AbsolutePath = _
  private var lichess: AbsolutePath = _
  private var akka: AbsolutePath = _

  @Setup(Level.Trial) def spawn(): Unit = {
    def existing(path: AbsolutePath): AbsolutePath = {
      assert(Files.exists(path.underlying))
      path
    }

    sbt = existing(AbsolutePath(TestUtil.getConfigDirForBenchmark("sbt")))
    lichess = existing(AbsolutePath(TestUtil.getConfigDirForBenchmark("lichess")))
    akka = existing(AbsolutePath(TestUtil.getConfigDirForBenchmark("akka")))
  }

  @Benchmark
  def loadSbtProject(): Unit = {
    loadProject(sbt)
  }

  @Benchmark
  def loadLichessProject(): Unit = {
    loadProject(lichess)
  }

  @Benchmark
  def loadAkkaProject(): Unit = {
    loadProject(akka)
  }
}
