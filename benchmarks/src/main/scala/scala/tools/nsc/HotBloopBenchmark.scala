package scala.tools.nsc

import java.io._
import java.nio.file._
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class HotBloopBenchmark {
  @Param(value = Array())
  var source: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

  @Param(value = Array("9fd80d31"))
  var bloopVersion: String = _

  @Param(value = Array("compile project"))
  var compileCmd: String = _

  // This parameter is set by ScalacBenchmarkRunner / UploadingRunner based on the Scala version.
  // When running the benchmark directly the "latest" symlink is used.
  @Param(value = Array("latest"))
  var corpusVersion: String = _

  var bloopProcess: Process = _
  var inputRedirect: ProcessBuilder.Redirect = _
  var outputRedirect: ProcessBuilder.Redirect = _
  var tempDir: Path = _
  var scalaHome: Path = _
  var processOutputReader: BufferedReader = _
  var processInputReader: BufferedWriter = _
  var output = new java.lang.StringBuilder()

  def buildDir(name: String): String =
    corpusSourcePath.toAbsolutePath.getParent.resolve(name).toString
  def buildDef =
    s"""
       |scalaVersion=2.12.4
       |name=project
       |classesDir=${buildDir("classes")}
       |classpath=/Users/martin/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.12.4/scala-library-2.12.4.jar
       |baseDirectory=${corpusSourcePath.toAbsolutePath.getParent.toString}
       |tmp=${buildDir("tmp")}
       |scalacOptions=
       |allScalaJars=foo
       |dependencies=
       |sourceDirectories=${corpusSourcePath.toAbsolutePath.toString}
       |javacOptions=
       |scalaOrganization=org.scala-lang
       |testFrameworks=
       |scalaName=scala-compiler
       |""".stripMargin

  @Setup(Level.Trial) def spawn(): Unit = {
    tempDir = Files.createTempDirectory("bloop-")
    val configDir = Files.createDirectory(tempDir.resolve(".bloop-config"))
    scalaHome = Files.createTempDirectory("scalaHome-")
    initDepsClasspath()
    Files.write(configDir.resolve("project.config"), buildDef.getBytes("UTF-8"))
    // val bloopShellPath = System.getProperty("bloop.shell")
    // if (bloopShellPath == null) sys.error("System property -Dbloop.shell absent")
    val bloopShellPath = "/Users/martin/.bloop/bloop-shell"
    val builder = new ProcessBuilder(bloopShellPath, ".")
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
    issue(s"$compileCmd")
    awaitPrompt()
    issue(s"persist")
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
      // println("read: " + buffer.take(read).mkString(""))
      if (read == -1) sys.error("EOF: " + output.toString)
      else {
        output.append(buffer, 0, read)
        if (output.toString.contains("> ")) {
          if (output.toString.contains("[E]")) sys.error(output.toString)
          return
        }
      }
    }

  }

  private def corpusSourcePath = Paths.get(s"../corpus/better-files/latest")

  def initDepsClasspath(): Unit = {
    val libDir = tempDir.resolve("lib")
    Files.createDirectories(libDir)
    for (depFile <- BenchmarkUtils.initDeps(corpusSourcePath)) {
      val libDirFile = libDir.resolve(depFile.getFileName)
      Files.copy(depFile, libDir)
    }

    val scalaHomeLibDir = scalaHome.resolve("lib")
    Files.createDirectories(scalaHomeLibDir)
    for (elem <- sys.props("java.class.path").split(File.pathSeparatorChar)) {
      val jarFile = Paths.get(elem)
      var name = jarFile.getFileName.toString
      if (name.startsWith("scala") && name.endsWith(".jar")) {
        if (name.startsWith("scala-library"))
          name = "scala-library.jar"
        else if (name.startsWith("scala-reflect"))
          name = "scala-reflect.jar"
        else if (name.startsWith("scala-compiler"))
          name = "scala-compiler.jar"
        Files.copy(jarFile, scalaHomeLibDir.resolve(name))
      }
    }

  }

  @TearDown(Level.Trial) def terminate(): Unit = {
    processOutputReader.close()
    bloopProcess.destroyForcibly()
    BenchmarkUtils.deleteRecursive(tempDir)
    BenchmarkUtils.deleteRecursive(scalaHome)
  }
}
