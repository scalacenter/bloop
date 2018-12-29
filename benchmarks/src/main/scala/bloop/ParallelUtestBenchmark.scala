package bloop

import java.io._
import java.util.concurrent.TimeUnit

import bloop.benchmarks.BuildInfo
import bloop.util.TestUtil
import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class ParallelUtestBenchmark {
  @Param(value = Array(""))
  var extraArgs: String = _

  var bloopProcess: Process = _
  var inputRedirect: ProcessBuilder.Redirect = _
  var outputRedirect: ProcessBuilder.Redirect = _
  var processOutputReader: BufferedReader = _
  var processInputReader: BufferedWriter = _
  var output = new java.lang.StringBuilder()

  @Setup(Level.Trial) def spawn(): Unit = {
    val configDir = TestUtil.getBloopConfigDir("utest")
    val base = configDir.getParent.getParent
    val bloopClasspath = BuildInfo.fullCompilationClasspath.map(_.getAbsolutePath).mkString(":")

    val builder = new ProcessBuilder(
      sys.props("java.home") + "/bin/java",
      "-Xms1G",
      "-Xmx2G",
      "-XX:ReservedCodeCacheSize=128m",
      "-cp",
      bloopClasspath,
      "bloop.Bloop",
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
    issue(s"compile root")
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
class SequentialUtestBenchmark {
  @Param(value = Array(""))
  var extraArgs: String = _

  var bloopProcess: Process = _
  var inputRedirect: ProcessBuilder.Redirect = _
  var outputRedirect: ProcessBuilder.Redirect = _
  var processOutputReader: BufferedReader = _
  var processInputReader: BufferedWriter = _
  var output = new java.lang.StringBuilder()

  @Setup(Level.Trial) def spawn(): Unit = {
    val configDir = TestUtil.getBloopConfigDir("utest")
    val base = configDir.getParent.getParent
    val bloopClasspath = BuildInfo.fullCompilationClasspath.map(_.getAbsolutePath).mkString(":")

    val builder = new ProcessBuilder(
      sys.props("java.home") + "/bin/java",
      "-Xms1G",
      "-Xmx2G",
      "-XX:ReservedCodeCacheSize=128m",
      "-cp",
      bloopClasspath,
      "bloop.Bloop",
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
    issue(s"compile utestJVM utestJS")
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
class PipelinedUtestBenchmark {
  @Param(value = Array(""))
  var extraArgs: String = _

  var bloopProcess: Process = _
  var inputRedirect: ProcessBuilder.Redirect = _
  var outputRedirect: ProcessBuilder.Redirect = _
  var processOutputReader: BufferedReader = _
  var processInputReader: BufferedWriter = _
  var output = new java.lang.StringBuilder()

  @Setup(Level.Trial) def spawn(): Unit = {
    val configDir = TestUtil.getBloopConfigDir("utest")
    val base = configDir.getParent.getParent
    val bloopClasspath = BuildInfo.fullCompilationClasspath.map(_.getAbsolutePath).mkString(":")

    val builder = new ProcessBuilder(
      sys.props("java.home") + "/bin/java",
      "-Xms1G",
      "-Xmx2G",
      "-XX:ReservedCodeCacheSize=128m",
      "-cp",
      bloopClasspath,
      "bloop.Bloop",
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
    issue(s"compile --pipeline utestJS")
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
