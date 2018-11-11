package bloop.tasks

import java.nio.charset.Charset
import java.nio.file._
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

import bloop.cli.Commands
import bloop.config.Config
import bloop.config.Config.CompileOrder
import bloop.data.{Origin, Project}
import bloop.engine.{Action, Build, BuildLoader, ExecutionContext, Interpreter, Run, State}
import bloop.exec.JavaEnv
import bloop.ScalaInstance
import bloop.io.AbsolutePath
import bloop.io.Paths.delete
import bloop.internal.build.BuildInfo
import bloop.logging.{BloopLogger, BufferedLogger, Logger, RecordingLogger}
import monix.eval.Task
import org.junit.Assert

import scala.concurrent.{Await, ExecutionException}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object TestUtil {
  def projectDir(base: Path, name: String) = base.resolve(name)

  def sourcesDir(base: Path, name: String) = projectDir(base, name).resolve("src")

  def classesDir(base: Path, name: String) = projectDir(base, name).resolve("classes")

  def getProject(name: String, state: State): Project =
    state.build.getProjectFor(name).getOrElse(sys.error(s"Project '$name' does not exist!"))

  def getBaseFromConfigDir(configDir: Path): Path = configDir.getParent.getParent

  val RootProject = "target-project"

  def checkAfterCleanCompilation(
      structures: Map[String, Map[String, String]],
      dependencies: Map[String, Set[String]],
      rootProjectName: String = RootProject,
      scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance,
      javaEnv: JavaEnv = JavaEnv.default,
      quiet: Boolean = false,
      failure: Boolean = false,
      useSiteLogger: Option[Logger] = None,
      order: CompileOrder = Config.Mixed
  )(afterCompile: State => Unit = (_ => ())) = {
    withState(structures, dependencies, scalaInstance, javaEnv, order) { (state: State) =>
      def action(state0: State): Unit = {
        val state = useSiteLogger.map(logger => state0.copy(logger = logger)).getOrElse(state0)
        // Check that this is a clean compile!
        val projects = state.build.projects
        assert(projects.forall(p => noPreviousAnalysis(p, state)))
        val action = Run(Commands.Compile(rootProjectName, incremental = true))
        val compiledState = TestUtil.blockingExecute(action, state)
        afterCompile(compiledState)
      }

      val logger = state.logger
      if (quiet) quietIfSuccess(logger)(newLogger => action(state.copy(logger = newLogger)))
      else if (failure) quietIfError(logger)(newLogger => action(state.copy(logger = newLogger)))
      else action(state)
    }
  }

  def await[T](duration: Duration)(t: Task[T]): T = {
    val handle = t
      .runAsync(ExecutionContext.scheduler)
    try Await.result(handle, duration)
    catch {
      case NonFatal(t) => handle.cancel(); throw t
      case i: InterruptedException => handle.cancel(); throw i
    }
  }

  def interpreterTask(a: Action, state: State): Task[State] = {
    Interpreter.execute(a, Task.now(state))
  }

  def blockOnTask[T](task: Task[T], seconds: Long): T = {
    val duration = Duration(seconds, TimeUnit.SECONDS)
    val handle = task.runAsync(ExecutionContext.scheduler)
    try Await.result(handle, duration)
    catch {
      case NonFatal(t) =>
        handle.cancel()
        t match {
          case e: ExecutionException => throw e.getCause()
          case _ => throw t
        }
    }
  }

  def blockingExecute(a: Action, state: State, duration: Duration = Duration.Inf): State = {
    val handle = interpreterTask(a, state).runAsync(ExecutionContext.scheduler)
    try Await.result(handle, duration)
    catch {
      case NonFatal(t) => handle.cancel(); throw t
      case i: InterruptedException => handle.cancel(); state
    }
  }

  def quietIfError[T](logger: Logger)(op: BufferedLogger => T): T = {
    val bufferedLogger = BufferedLogger(logger.asVerbose)
    try op(bufferedLogger)
    catch {
      case ex: Throwable => bufferedLogger.clear(); throw ex
    }
  }

  def quietIfSuccess[T](logger: Logger)(op: BufferedLogger => T): T = {
    val bufferedLogger = BufferedLogger(logger.asVerbose)
    try op(bufferedLogger)
    catch {
      case ex: Throwable => bufferedLogger.flush(); throw ex
    }
  }

  private final val integrationsIndexPath = BuildInfo.buildIntegrationsIndex.toPath
  private final val benchmarksIndexPath = BuildInfo.localBenchmarksIndex.toPath

  private[bloop] lazy val testProjectsIndex = indexFromPath(integrationsIndexPath, false)
  private[bloop] lazy val localBenchmarksIndex = indexFromPath(benchmarksIndexPath, true)
  private[bloop] def indexFromPath(
      target: Path,
      allowMissing: Boolean
  ): Map[String, Path] = {
    if (Files.exists(target)) {
      import scala.collection.JavaConverters._
      val lines = Files.readAllLines(target).asScala
      val entries = lines.map(line => line.split(",").toList)
      entries.map {
        case List(key, value) => key -> Paths.get(value)
        case _ => sys.error(s"Malformed index file: ${lines.mkString(System.lineSeparator)}")
      }.toMap
    } else if (!allowMissing) sys.error(s"Missing integration index at ${target}!")
    else Map.empty
  }

  def getConfigDirForBenchmark(name: String): Path = {
    testProjectsIndex
      .get(name)
      .orElse(localBenchmarksIndex.get(name))
      .getOrElse(sys.error(s"Project ${name} does not exist at ${integrationsIndexPath}"))
  }

  def getBloopConfigDir(name: String): Path = {
    testProjectsIndex
      .get(name)
      .getOrElse(sys.error(s"Project ${name} does not exist at ${integrationsIndexPath}"))
  }

  private final val ThisClassLoader = this.getClass.getClassLoader
  def loadTestProject(
      name: String,
      transformProjects: List[Project] => List[Project] = identity
  ): State = {
    val baseDirURL = ThisClassLoader.getResource(name)
    if (baseDirURL == null) {
      // The project is not in `test/resources`, let's load it from the integrations directory
      loadTestProject(getBloopConfigDir(name), name, transformProjects)
    } else {
      val baseDir = java.nio.file.Paths.get(baseDirURL.toURI)
      val bloopConfigDir = baseDir.resolve("bloop-config")
      if (Files.exists(bloopConfigDir)) {
        loadTestProject(bloopConfigDir, name, transformProjects)
      } else {
        // The project is not an integration test, let's load it from the integrations directory
        loadTestProject(getBloopConfigDir(name), name, transformProjects)
      }
    }
  }

  def loadTestProject(
      configDir: Path,
      name: String,
      transformProjects: List[Project] => List[Project]
  ): State = {
    val logger = BloopLogger.default(configDir.toString())
    assert(Files.exists(configDir), "Does not exist: " + configDir)

    val configDirectory = AbsolutePath(configDir)
    val loadedProjects = transformProjects(BuildLoader.loadSynchronously(configDirectory, logger))
    val build = Build(configDirectory, loadedProjects)
    val state = State.forTests(build, CompilationHelpers.getCompilerCache(logger), logger)
    state.copy(commonOptions = state.commonOptions.copy(env = runAndTestProperties))
  }

  private[bloop] final val runAndTestProperties = {
    val props = new bloop.cli.CommonOptions.PrettyProperties()
    props.put("BLOOP_OWNER", "owner")
    props
  }

  /**
   * Compile the given sources and then run `cmd`. Log messages are then given to `check`.
   *
   * @param sources The sources to compile.
   * @param cmd     The command to execute after compiling.
   * @param check   A function that'll receive the resulting log messages.
   */
  def runAndCheck(sources: Seq[String], cmd: Commands.CompilingCommand)(
      check: List[(String, String)] => Unit): Unit = {
    val noDependencies = Map.empty[String, Set[String]]
    val namedSources = sources.zipWithIndex.map { case (src, idx) => s"src$idx.scala" -> src }.toMap
    val projectsStructure = Map(cmd.project -> namedSources)
    val javaEnv = JavaEnv.default
    checkAfterCleanCompilation(
      projectsStructure,
      noDependencies,
      rootProjectName = cmd.project,
      javaEnv = javaEnv,
      quiet = true) { state => runAndCheck(state, cmd)(check)
    }
  }

  /**
   * Executes the given `cmd` on `state`. The resulting log messages are passed to `check`.
   *
   * @param state The current state
   * @param cmd   The command to execute.
   * @param check A function that'll receive the resulting log messages.
   */
  def runAndCheck(state: State, cmd: Commands.CompilingCommand)(
      check: List[(String, String)] => Unit): Unit = {
    val recordingLogger = new RecordingLogger
    val commonOptions = state.commonOptions.copy(env = runAndTestProperties)
    val recordingState = state.copy(logger = recordingLogger).copy(commonOptions = commonOptions)
    TestUtil.blockingExecute(Run(cmd), recordingState)
    check(recordingLogger.getMessages)
  }

  def withState[T](
      projectStructures: Map[String, Map[String, String]],
      dependencies: Map[String, Set[String]],
      scalaInstance: ScalaInstance,
      javaEnv: JavaEnv,
      order: CompileOrder = Config.Mixed
  )(op: State => T): T = {
    withTemporaryDirectory { temp =>
      val logger = BloopLogger.default(temp.toString)
      val projects = projectStructures.map {
        case (name, sources) =>
          val projectDeps = dependencies.getOrElse(name, Set.empty)
          makeProject(temp, name, sources, projectDeps, Some(scalaInstance), javaEnv, logger, order)
      }
      val build = Build(AbsolutePath(temp), projects.toList)
      val state = State.forTests(build, CompilationHelpers.getCompilerCache(logger), logger)
      op(state)
    }
  }

  def noPreviousAnalysis(project: Project, state: State): Boolean =
    !state.results.lastSuccessfulResultOrEmpty(project).analysis().isPresent

  def hasPreviousResult(project: Project, state: State): Boolean =
    state.results.lastSuccessfulResult(project).isDefined

  private[bloop] def syntheticOriginFor(path: AbsolutePath): Origin =
    Origin(path, FileTime.fromMillis(0), scala.util.Random.nextInt())

  def makeProject(
      baseDir: Path,
      name: String,
      sources: Map[String, String],
      dependencies: Set[String],
      scalaInstance: Option[ScalaInstance],
      javaEnv: JavaEnv,
      logger: Logger,
      compileOrder: CompileOrder
  ): Project = {
    val origin = syntheticOriginFor(AbsolutePath(baseDir))
    val baseDirectory = projectDir(baseDir, name)
    val (srcs, classes) = makeProjectStructure(baseDir, name)
    val tempDir = baseDirectory.resolve("tmp")
    Files.createDirectories(tempDir)

    val target = classesDir(baseDir, name)
    val depsTargets = (dependencies.map(classesDir(baseDir, _))).map(AbsolutePath.apply).toList
    val allJars = scalaInstance.map(_.allJars.map(AbsolutePath.apply)).getOrElse(Array.empty)
    val classpath = depsTargets ++ allJars
    val sourceDirectories = List(AbsolutePath(srcs))
    writeSources(srcs, sources)
    Project(
      name = name,
      baseDirectory = AbsolutePath(baseDirectory),
      dependencies = dependencies.toList,
      scalaInstance = scalaInstance,
      rawClasspath = classpath,
      compileSetup = Config.CompileSetup.empty.copy(order = compileOrder),
      classesDir = AbsolutePath(target),
      scalacOptions = Nil,
      javacOptions = Nil,
      sources = sourceDirectories,
      testFrameworks = Nil,
      testOptions = Config.TestOptions.empty,
      out = AbsolutePath(baseDirectory), // This means nothing in tests
      // Let's store the analysis file in target even though we usually do it in `out`
      analysisOut = AbsolutePath(target.resolve(Config.Project.analysisFileName(name))),
      platform = Project.defaultPlatform(logger, Some(javaEnv)),
      sbt = None,
      resolution = None,
      origin = origin
    )
  }

  def makeProjectStructure(base: Path, name: String): (Path, Path) = {
    val srcs = sourcesDir(base, name)
    val classes = classesDir(base, name)
    Files.createDirectories(srcs)
    Files.createDirectories(classes)
    (srcs, classes)
  }

  def ensureCompilationInAllTheBuild(state: State): Unit = {
    state.build.projects.foreach { p =>
      Assert.assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
    }
  }

  def writeSources(srcDir: Path, sources: Map[String, String]): Unit = {
    sources.foreach {
      case (name, contents) =>
        val writer = Files.newBufferedWriter(srcDir.resolve(name), Charset.forName("UTF-8"))
        try writer.write(contents)
        finally writer.close()
    }
  }

  def withTemporaryDirectory[T](op: Path => T): T = {
    val temp = Files.createTempDirectory("tmp-test")
    try op(temp)
    finally delete(AbsolutePath(temp))
  }

  def withTemporaryFile[T](op: Path => T): T = {
    val temp = Files.createTempFile("tmp", "")
    try op(temp)
    finally delete(AbsolutePath(temp))
  }

  def errorsFromLogger(logger: RecordingLogger): List[String] =
    logger.getMessages.iterator.filter(_._1 == "error").map(_._2).toList
}
