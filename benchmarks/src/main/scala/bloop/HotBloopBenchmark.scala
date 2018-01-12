package bloop

import java.io._
import java.nio.file._
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._

import scala.tools.nsc.BenchmarkUtils
import bloop.tasks.ProjectHelpers

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class HotBloopBenchmark {
  @Param(value = Array())
  var project: String = _

  @Param(value = Array())
  var projectName: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

  var bloopProcess: Process = _
  var inputRedirect: ProcessBuilder.Redirect = _
  var outputRedirect: ProcessBuilder.Redirect = _
  var tempDir: Path = _
  var processOutputReader: BufferedReader = _
  var processInputReader: BufferedWriter = _
  var output = new java.lang.StringBuilder()

  @Setup(Level.Trial) def spawn(): Unit = {
    tempDir = Files.createTempDirectory("bloop-")
    val configDir = Files.createDirectory(tempDir.resolve(".bloop-config"))

    val path = ProjectHelpers.testProjectsBase
    val state = ProjectHelpers.loadTestProject(path, project)
    state.build.projects.foreach { project =>
      val outPath = configDir.resolve(s"${project.name}.config")
      val stream = new FileOutputStream(outPath.toFile)
      try project.toProperties.store(stream, null)
      finally stream.close()
    }

    val bloopJarPath = System.getProperty("bloop.jar")
    if (bloopJarPath == null) sys.error("System property -Dbloop.jar absent")

    val builder = new ProcessBuilder(sys.props("java.home") + "/bin/java",
                                     "-Xms2G",
                                     "-Xmx2G",
                                     "-jar",
                                     bloopJarPath,
                                     ".")
    builder.directory(tempDir.toFile)
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
        if (output.toString.contains("\n> ")) {
          if (output.toString.contains("[E]")) sys.error(output.toString)
          return
        }
      }
    }

  }

  @TearDown(Level.Trial) def terminate(): Unit = {
    processOutputReader.close()
    bloopProcess.destroyForcibly()
    BenchmarkUtils.deleteRecursive(tempDir)
  }
}
