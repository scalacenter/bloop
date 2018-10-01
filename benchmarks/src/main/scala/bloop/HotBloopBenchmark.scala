package bloop

import java.io._
import java.nio.file._
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._
import bloop.tasks.TestUtil
import pl.project13.scala.jmh.extras.profiler.ForkedAsyncProfiler

@State(Scope.Benchmark)
abstract class HotBloopBenchmarkBase {
  val bloopCompileFlags: Option[String]

  @Param(value = Array())
  var project: String = _

  @Param(value = Array())
  var projectName: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

  @Param(value = Array("false"))
  var noIncremental: Boolean = false

  @Param(value = Array(""))
  var pidFile: String = _

  var bloopProcess: Process = _
  var inputRedirect: ProcessBuilder.Redirect = _
  var outputRedirect: ProcessBuilder.Redirect = _
  var processOutputReader: BufferedReader = _
  var processInputReader: BufferedWriter = _
  var output = new java.lang.StringBuilder()

  def findMaxHeap(project: String): String = project match {
    case "lichess" | "akka" => "-Xmx3G"
    case _ => "-Xmx2G"
  }

  import bloop.benchmarks.BuildInfo
  @Setup(Level.Trial) def spawn(): Unit = {
    val configDir = TestUtil.getConfigDirForBenchmark(project)
    val base = configDir.getParent
    val bloopClasspath = BuildInfo.fullCompilationClasspath.map(_.getAbsolutePath).mkString(":")

    val jvmArgs = {
      val defaultJvmArgs = List(
        sys.props("java.home") + "/bin/java",
        "-Xms2G",
        findMaxHeap(project),
        "-XX:ReservedCodeCacheSize=128m"
      )
      if (noIncremental) defaultJvmArgs ::: List("-Dbloop.zinc.disabled=true")
      else defaultJvmArgs
    }

    val allArgs = jvmArgs ++ List(
      "-cp",
      bloopClasspath,
      "bloop.Bloop",
      "--config-dir",
      configDir.toAbsolutePath.toString
    )

    import scala.collection.JavaConverters._
    val builder = new ProcessBuilder(allArgs.asJava)
    builder.redirectErrorStream(true)
    builder.directory(base.toFile)
    inputRedirect = builder.redirectInput()
    outputRedirect = builder.redirectOutput()
    bloopProcess = builder.start()

    if (pidFile.nonEmpty) {
      val pid = ForkedAsyncProfiler.getPidOfProcess(bloopProcess)
      if (pid == -1) sys.error("PID could not be found! Async profiler cannot be used.")
      else {
        //println(s"Writing ${pid} to ${pidFile}")
        Files.write(Paths.get(pidFile), pid.toString.getBytes())
      }
    }

    processOutputReader = new BufferedReader(new InputStreamReader(bloopProcess.getInputStream))
    processInputReader = new BufferedWriter(new OutputStreamWriter(bloopProcess.getOutputStream))
    awaitPrompt()
  }

  @Benchmark
  def compile(): Unit = {
    issue(s"clean")
    awaitPrompt()
    bloopCompileFlags match {
      case Some(flags) => issue(s"compile $flags $projectName")
      case None => issue(s"compile $projectName")
    }
    awaitPrompt()
  }

  def issue(str: String) = {
    processInputReader.write(str + "\n")
    processInputReader.flush()
  }

  def awaitPrompt(): Unit = {
    output.setLength(0)
    var line = ""
    val buffer = new Array[Char](128)
    var read: Int = -1
    while (true) {
      read = processOutputReader.read(buffer)
      if (read == -1) sys.error("EOF: " + output.toString)
      else {
        output.append(buffer, 0, read)
        if (output.toString.contains("shell> ")) {
          if (output.toString.contains("[E]")) sys.error(output.toString)
          return
        }
      }
    }

  }

  @TearDown(Level.Trial) def terminate(): Unit = {
    if (pidFile.isEmpty) {
      processOutputReader.close()
      bloopProcess.destroyForcibly()
    } else {
      // Async profiler needs alive PID to close jattach, so only close process at the very end
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run(): Unit = {
          processOutputReader.close()
          bloopProcess.destroyForcibly()
          ()
        }
      })
    }
    ()
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class HotBloopBenchmark extends HotBloopBenchmarkBase {
  override val bloopCompileFlags: Option[String] = None
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class HotPipelinedBloopBenchmark extends HotBloopBenchmarkBase {
  override val bloopCompileFlags: Option[String] = Some("--pipeline")
}
