package bloop

import java.io._
import java.nio.file._
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._

import bloop.tasks.TestUtil

@State(Scope.Benchmark)
abstract class HotBloopBenchmarkBase {
  val bloopCompileFlags: Option[String]

  @Param(value = Array())
  var project: String = _

  @Param(value = Array())
  var projectName: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

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

  @Setup(Level.Trial) def spawn(): Unit = {
    val configDir = TestUtil.getConfigDirForBenchmark(project)
    val base = configDir.getParent
    val bloopJarPath = System.getProperty("bloop.jar")
    if (bloopJarPath == null) sys.error("System property -Dbloop.jar absent")

    val builder = new ProcessBuilder(
      sys.props("java.home") + "/bin/java",
      "-Xms2G",
      findMaxHeap(project),
      "-XX:ReservedCodeCacheSize=128m",
      "-jar",
      bloopJarPath,
      "--config-dir",
      configDir.toAbsolutePath.toString
    )

    builder.redirectErrorStream(true)
    builder.directory(base.toFile)
    inputRedirect = builder.redirectInput()
    outputRedirect = builder.redirectOutput()
    bloopProcess = builder.start()
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
    processOutputReader.close()
    bloopProcess.destroyForcibly()
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
  override val bloopCompileFlags: Option[String] = Some("--pipelined")
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class HotParallelBloopBenchmark extends HotBloopBenchmarkBase {
  override val bloopCompileFlags: Option[String] = Some("--parallel")
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class HotPipelinedParallelBloopBenchmark extends HotBloopBenchmarkBase {
  override val bloopCompileFlags: Option[String] = Some("--pipelined-parallel")
}

@State(Scope.Benchmark)
class HotBloopNoIncrementalBenchmark {
  @Param(value = Array())
  var project: String = _

  @Param(value = Array())
  var projectName: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

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

  @Setup(Level.Trial) def spawn(): Unit = {
    val configDir = TestUtil.getConfigDirForBenchmark(project)
    val base = configDir.getParent
    val bloopJarPath = System.getProperty("bloop.jar")
    if (bloopJarPath == null) sys.error("System property -Dbloop.jar absent")

    val builder = new ProcessBuilder(
      sys.props("java.home") + "/bin/java",
      "-Dbloop.zinc.disabled=true",
      "-Xms2G",
      findMaxHeap(project),
      "-XX:+UseShenandoahGC", // It will be ignored by all JVMs except the one it has it
      "-XX:ReservedCodeCacheSize=128m",
      "-jar",
      bloopJarPath,
      "--config-dir",
      configDir.toAbsolutePath.toString
    )

    builder.redirectErrorStream(true)
    builder.directory(base.toFile)
    inputRedirect = builder.redirectInput()
    outputRedirect = builder.redirectOutput()
    bloopProcess = builder.start()
    processOutputReader = new BufferedReader(new InputStreamReader(bloopProcess.getInputStream))
    processInputReader = new BufferedWriter(new OutputStreamWriter(bloopProcess.getOutputStream))
    awaitPrompt()
  }

  @Benchmark
  def compile(): Unit = {
    issue(s"clean")
    awaitPrompt()
    issue(s"compile $projectName")
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
    processOutputReader.close()
    bloopProcess.destroyForcibly()
    ()
  }
}
