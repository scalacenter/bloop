package bloop

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
class HotSbtBenchmark {
  @Param(value = Array())
  var project: String = _

  @Param(value = Array())
  var projectName: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

  var sbtProcess: Process = _
  var inputRedirect: ProcessBuilder.Redirect = _
  var outputRedirect: ProcessBuilder.Redirect = _
  var path: Path = _
  var cleanClassesPath: Path = _
  var processOutputReader: BufferedReader = _
  var processInputReader: BufferedWriter = _
  var output = new java.lang.StringBuilder()

  var sbtCommand: String = _

  private def cleanClassesPlugin: String =
    s"""package bloop
       |import sbt._
       |import Keys._
       |
       |object CleanClassesPlugin extends AutoPlugin {
       |  override def trigger = allRequirements
       |  override def requires = plugins.JvmPlugin
       |
       |  object autoImport {
       |    lazy val cleanClasses = taskKey[Unit]("Clean all classes in all projects")
       |    lazy val cleanClassesTask = taskKey[Unit]("Clean all classes")
       |  }
       |
       |  import autoImport._
       |
       |  override def globalSettings: Seq[Setting[_]] = Seq(
       |    cleanClasses := Def.taskDyn {
       |      cleanClassesTask.all(ScopeFilter(inAnyProject))
       |    }.value
       |  )
       |
       |  override def projectSettings: Seq[Setting[_]] = Seq(
       |    cleanClassesTask := {
       |      IO.delete(classDirectory.in(Compile).value)
       |      IO.delete(classDirectory.in(Test).value)
       |    }
       |  )
       |}""".stripMargin

  def findMaxHeap(project: String): String = project match {
    case "lichess" | "akka" | "scio" | "summingbird" | "http4s" | "gatling" => "-Xmx4G"
    case _ => "-Xmx3G"
  }

  @Setup(Level.Trial) def spawn(): Unit = {
    sbtCommand = {
      if (projectName.endsWith("-test")) s"${projectName.stripSuffix("-test")}/test:compile"
      else s"${projectName}/compile"
    }

    path = CommunityBuild.getConfigDirForBenchmark(project).getParent
    cleanClassesPath = path.resolve("project").resolve("CleanClassesPlugin.scala")
    Files.write(cleanClassesPath, cleanClassesPlugin.getBytes("UTF-8"))
    val sbtLaucherPath = System.getProperty("sbt.launcher")
    if (sbtLaucherPath == null) sys.error("System property -Dsbt.launcher absent")
    val maxHeap = findMaxHeap(project)
    val builder = new ProcessBuilder(
      sys.props("java.home") + "/bin/java",
      "-Xms2G",
      maxHeap,
      "-XX:ReservedCodeCacheSize=256m",
      "-Dsbt.log.format=false",
      "-jar",
      sbtLaucherPath
    )
    builder.directory(path.toFile)
    inputRedirect = builder.redirectInput()
    outputRedirect = builder.redirectOutput()
    sbtProcess = builder.start()
    processOutputReader = new BufferedReader(new InputStreamReader(sbtProcess.getInputStream))
    processInputReader = new BufferedWriter(new OutputStreamWriter(sbtProcess.getOutputStream))
    issue("""set every shellPrompt := (_ => "> ")""")
    awaitPrompt()
  }

  @Benchmark
  def compile(): Unit = {
    issue(s";cleanClasses;$sbtCommand")
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
          if (output.toString.contains("[error")) sys.error(output.toString)
          return
        }
      }
    }

  }

  @TearDown(Level.Trial) def terminate(): Unit = {
    processOutputReader.close()
    sbtProcess.destroyForcibly()
    Files.delete(cleanClassesPath)
  }
}
