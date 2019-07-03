package bloop.util

import java.io.File
import java.nio.charset.Charset
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import bloop.{CompilerCache, ScalaInstance}
import bloop.cli.Commands
import bloop.config.Config
import bloop.config.Config.CompileOrder
import bloop.data.{ClientInfo, Origin, Project}
import bloop.engine.{Action, Build, BuildLoader, ExecutionContext, Interpreter, Run, State}
import bloop.exec.JavaEnv
import bloop.engine.caches.ResultsCache
import bloop.internal.build.BuildInfo
import bloop.io.Paths.delete
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{
  BloopLogger,
  BspClientLogger,
  BufferedLogger,
  DebugFilter,
  Logger,
  RecordingLogger
}

import _root_.monix.eval.Task
import _root_.monix.execution.Scheduler

import org.junit.Assert
import sbt.internal.inc.bloop.ZincInternals

import scala.concurrent.duration.{Duration, FiniteDuration, TimeUnit}
import scala.concurrent.{Await, ExecutionException}
import scala.meta.jsonrpc.Services
import scala.tools.nsc.Properties
import scala.util.control.NonFatal

object TestUtil {
  def projectDir(base: Path, name: String) = base.resolve(name)
  def sourcesDir(base: Path, name: String) = projectDir(base, name).resolve("src")
  def classesDir(base: Path, name: String) = projectDir(base, name).resolve("classes")
  def getBaseFromConfigDir(configDir: Path): Path = configDir.getParent.getParent
  def getProject(name: String, state: State): Project =
    state.build.getProjectFor(name).getOrElse(sys.error(s"Project '$name' does not exist!"))

  final val componentProvider =
    ZincInternals.getComponentProvider(bloop.io.Paths.getCacheDirectory("components"))

  private var singleCompilerCache: CompilerCache = null
  def getCompilerCache(logger: Logger): CompilerCache = synchronized {
    if (singleCompilerCache != null) singleCompilerCache
    else {
      val jars = bloop.io.Paths.getCacheDirectory("scala-jars")
      singleCompilerCache = new CompilerCache(componentProvider, jars, logger, Nil)
      singleCompilerCache
    }
  }

  final lazy val scalaInstance: ScalaInstance = {
    ScalaInstance.resolve(
      "org.scala-lang",
      "scala-compiler",
      Properties.versionNumberString,
      bloop.logging.NoopLogger
    )(bloop.engine.ExecutionContext.ioScheduler)
  }

  final val RootProject = "target-project"
  def checkAfterCleanCompilation(
      structures: Map[String, Map[String, String]],
      dependencies: Map[String, Set[String]],
      rootProjects: List[String] = List(RootProject),
      scalaInstance: ScalaInstance = TestUtil.scalaInstance,
      javaEnv: JavaEnv = JavaEnv.default,
      quiet: Boolean = false,
      failure: Boolean = false,
      useSiteLogger: Option[Logger] = None,
      order: CompileOrder = Config.Mixed
  )(afterCompile: State => Unit = (_ => ())) = {
    testState(structures, dependencies, scalaInstance, javaEnv, order) { (state: State) =>
      def action(state0: State): Unit = {
        val state = useSiteLogger.map(logger => state0.copy(logger = logger)).getOrElse(state0)
        // Check that this is a clean compile!
        val projects = state.build.projects
        assert(projects.forall(p => noPreviousAnalysis(p, state)))
        val action = Run(Commands.Compile(rootProjects, incremental = true))
        val compiledState = TestUtil.blockingExecute(action, state)
        afterCompile(compiledState)
      }

      val logger = state.logger
      if (quiet) quietIfSuccess(logger)(newLogger => action(state.copy(logger = newLogger)))
      else if (failure) quietIfError(logger)(newLogger => action(state.copy(logger = newLogger)))
      else action(state)
    }
  }

  def await[T](duration: Duration, scheduler: Scheduler)(t: Task[T]): T = {
    val handle = t.runAsync(scheduler)
    try Await.result(handle, duration)
    catch {
      case NonFatal(t) => handle.cancel(); throw t
      case i: InterruptedException => handle.cancel(); throw i
    }
  }

  def await[T](length: Long, unit: TimeUnit)(t: Task[T]): T = {
    await(FiniteDuration(length, unit))(t)
  }

  def await[T](duration: Duration)(t: Task[T]): T = {
    await(duration, ExecutionContext.scheduler)(t)
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

  def getBloopConfigDir(buildName: String): Path = {
    val baseDirURL = ThisClassLoader.getResource(buildName)
    if (baseDirURL == null) {
      sys.error(s"Project ${buildName} does not exist in test resources")
    } else {
      val baseDir = java.nio.file.Paths.get(baseDirURL.toURI)
      val bloopConfigDir = baseDir.resolve("bloop-config")
      if (Files.exists(bloopConfigDir)) bloopConfigDir
      else sys.error(s"Project ${buildName} does not exist in test resources")
    }
  }

  private final val ThisClassLoader = this.getClass.getClassLoader

  def loadTestProject(buildName: String): State = {
    val configDir = getBloopConfigDir(buildName)
    val logger = BloopLogger.default(configDir.toString())
    loadTestProject(configDir, logger, true)
  }

  def loadTestProject(buildName: String, logger: Logger): State =
    loadTestProject(getBloopConfigDir(buildName), logger, true)

  def loadTestProject(configDir: Path): State = {
    val logger = BloopLogger.default(configDir.toString())
    loadTestProject(configDir, logger, false)
  }

  def loadTestProject(configDir: Path, logger: Logger): State =
    loadTestProject(configDir, logger, false, identity[List[Project]] _)

  def loadTestProject(configDir: Path, logger: Logger, emptyResults: Boolean): State =
    loadTestProject(configDir, logger, emptyResults, identity[List[Project]] _)

  def loadTestProject(
      configDir: Path,
      logger: Logger,
      emptyResults: Boolean,
      transformProjects: List[Project] => List[Project]
  ): State = {
    assert(Files.exists(configDir), "Does not exist: " + configDir)

    val configDirectory = AbsolutePath(configDir)
    val loadedProjects = transformProjects(BuildLoader.loadSynchronously(configDirectory, logger))
    val build = Build(configDirectory, loadedProjects)
    val state = State.forTests(build, TestUtil.getCompilerCache(logger), logger)
    val state1 = state.copy(commonOptions = state.commonOptions.copy(env = runAndTestProperties))
    if (!emptyResults) state1 else state1.copy(results = ResultsCache.emptyForTests)
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
  def runAndCheck(projectName: String, sources: Seq[String], cmd: Commands.CompilingCommand)(
      check: List[(String, String)] => Unit
  ): Unit = {
    val noDependencies = Map.empty[String, Set[String]]
    val namedSources = sources.zipWithIndex.map { case (src, idx) => s"src$idx.scala" -> src }.toMap
    val projectsStructure = Map(projectName -> namedSources)
    val javaEnv = JavaEnv.default
    checkAfterCleanCompilation(
      projectsStructure,
      noDependencies,
      rootProjects = List(projectName),
      javaEnv = javaEnv,
      quiet = true
    ) { state =>
      runAndCheck(state, cmd)(check)
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
      check: List[(String, String)] => Unit
  ): Unit = {
    val recordingLogger = new RecordingLogger
    val commonOptions = state.commonOptions.copy(env = runAndTestProperties)
    val recordingState = state.copy(logger = recordingLogger).copy(commonOptions = commonOptions)
    TestUtil.blockingExecute(Run(cmd), recordingState)
    try check(recordingLogger.getMessages)
    catch {
      case NonFatal(t) =>
        recordingLogger.dump()
        throw t
    }
  }

  def testState[T](
      projectStructures: Map[String, Map[String, String]],
      dependenciesMap: Map[String, Set[String]],
      instance: ScalaInstance = TestUtil.scalaInstance,
      env: JavaEnv = JavaEnv.default,
      order: CompileOrder = Config.Mixed,
      userLogger: Option[Logger] = None,
      extraJars: Array[AbsolutePath] = Array()
  )(op: State => T): T = {
    withinWorkspace { temp =>
      val logger = userLogger.getOrElse(BloopLogger.default(temp.toString))
      val projects = projectStructures.map {
        case (name, sources) =>
          val deps = dependenciesMap.getOrElse(name, Set.empty)
          makeProject(temp, name, sources, deps, Some(instance), env, logger, order, extraJars)
      }
      val build = Build(temp, projects.toList)
      val state = State.forTests(build, TestUtil.getCompilerCache(logger), logger)
      try op(state)
      catch {
        case NonFatal(t) =>
          userLogger match {
            case Some(logger: RecordingLogger) =>
              System.err.println("Printing logs before rethrowing exception...")
              logger.dump()
            case _ => ()
          }

          throw t
      }
    }
  }

  def noPreviousAnalysis(project: Project, state: State): Boolean =
    !state.results.lastSuccessfulResultOrEmpty(project).previous.analysis().isPresent

  def hasPreviousResult(project: Project, state: State): Boolean =
    state.results.lastSuccessfulResult(project).isDefined

  private[bloop] def syntheticOriginFor(path: AbsolutePath): Origin =
    Origin(path, FileTime.fromMillis(0), scala.util.Random.nextInt())

  def makeProject(
      baseDir: AbsolutePath,
      name: String,
      sources: Map[String, String],
      dependencies: Set[String],
      scalaInstance: Option[ScalaInstance],
      javaEnv: JavaEnv,
      logger: Logger,
      compileOrder: CompileOrder,
      extraJars: Array[AbsolutePath]
  ): Project = {
    val origin = syntheticOriginFor(baseDir)
    val baseDirectory = projectDir(baseDir.underlying, name)
    val ProjectArchetype(srcs, _, _, classes) = createProjectArchetype(baseDir.underlying, name)
    val tempDir = baseDirectory.resolve("tmp")
    Files.createDirectories(tempDir)
    val target = classesDir(baseDir.underlying, name)

    // Requires dependencies to be transitively listed
    val depsTargets =
      (dependencies.map(classesDir(baseDir.underlying, _))).map(AbsolutePath.apply).toList
    val allJars = scalaInstance.map(_.allJars.map(AbsolutePath.apply)).getOrElse(Array.empty)
    val classpath = depsTargets ++ allJars ++ extraJars
    val sourceDirectories = List(srcs)
    val testFrameworks =
      if (classpath.exists(_.syntax.contains("junit"))) List(Config.TestFramework.JUnit) else Nil

    writeFilesToBase(srcs, sources.map(kv => RelativePath(kv._1) -> kv._2))
    Project(
      name = name,
      baseDirectory = AbsolutePath(baseDirectory),
      dependencies = dependencies.toList,
      scalaInstance = scalaInstance,
      rawClasspath = classpath,
      resources = Nil,
      compileSetup = Config.CompileSetup.empty.copy(order = compileOrder),
      genericClassesDir = AbsolutePath(target),
      scalacOptions = Nil,
      javacOptions = Nil,
      sources = sourceDirectories,
      testFrameworks = testFrameworks,
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

  case class ProjectArchetype(
      sourceDir: AbsolutePath,
      targetDir: AbsolutePath,
      resourcesDir: AbsolutePath,
      classesDir: AbsolutePath
  )

  def createProjectArchetype(base: Path, name: String): ProjectArchetype = {
    val sourceDir = sourcesDir(base, name)
    val targetDir = base.resolve("target")
    val resourcesDir = base.resolve("resources")
    val classes = classesDir(targetDir, name)
    Files.createDirectories(sourceDir)
    Files.createDirectories(targetDir)
    Files.createDirectories(resourcesDir)
    Files.createDirectories(classes)
    ProjectArchetype(
      AbsolutePath(sourceDir),
      AbsolutePath(targetDir),
      AbsolutePath(resourcesDir),
      AbsolutePath(classes)
    )
  }

  def ensureCompilationInAllTheBuild(state: State): Unit = {
    state.build.projects.foreach { p =>
      Assert.assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
    }
  }

  def writeFilesToBase(base: AbsolutePath, pathsToContents: Map[RelativePath, String]): Unit = {
    pathsToContents.foreach {
      case (relativePath, contents) =>
        val fullPath = relativePath.toAbsolute(base)
        Files.createDirectories(fullPath.getParent.underlying)
        val writer = Files.newBufferedWriter(fullPath.underlying, Charset.forName("UTF-8"))
        try writer.write(contents)
        finally writer.close()
    }
  }

  /** Creates an empty workspace where operations can happen. */
  def withinWorkspace[T](op: AbsolutePath => T): T = {
    val temp = Files.createTempDirectory("bloop-test-workspace")
    try op(AbsolutePath(temp))
    finally delete(AbsolutePath(temp))
  }

  def withTemporaryFile[T](op: Path => T): T = {
    val temp = Files.createTempFile("tmp", "")
    try op(temp)
    finally delete(AbsolutePath(temp))
  }

  def errorsFromLogger(logger: RecordingLogger): List[String] =
    logger.getMessages.iterator.filter(_._1 == "error").map(_._2).toList

  /** Fails the test with a pretty diff if there obtained is not the same as expected */
  def assertNoDiff(expected: String, obtained: String): Unit = {
    import scala.collection.JavaConverters._
    if (obtained.isEmpty && !expected.isEmpty) Assert.fail("obtained empty output")
    def splitLines(string: String): java.util.List[String] =
      string.trim.replace("\r\n", "\n").split("\n").toSeq.asJava
    val obtainedLines = splitLines(obtained)
    val expectedLines = splitLines(expected)
    val patch = difflib.DiffUtils.diff(expectedLines, obtainedLines)
    val diff =
      if (patch.getDeltas.isEmpty) ""
      else {
        difflib.DiffUtils
          .generateUnifiedDiff(
            "expected",
            "obtained",
            expectedLines,
            patch,
            1
          )
          .asScala
          .mkString("\n")
      }
    if (!diff.isEmpty) {
      Assert.fail("\n" + diff)
    }
  }

  def universalPath(path: String): String = {
    path.split("/").mkString(File.separator)
  }

  def createSimpleRecursiveBuild(bloopDir: RelativePath): AbsolutePath = {
    import bloop.config.Config
    val baseDir = Files.createTempDirectory("bloop-recursive-project")
    baseDir.toFile.deleteOnExit()
    val configDir = AbsolutePath(baseDir).resolve(bloopDir)
    Files.createDirectory(configDir.underlying)
    val jsonTargetG = configDir.resolve("g.json").underlying
    val outDir = Files.createDirectory(baseDir.resolve("out"))
    val classesDir = Files.createDirectory(outDir.resolve("classes"))

    // format: OFF
    val configFileG = bloop.config.Config.File(Config.File.LatestVersion, Config.Project("g", baseDir, Nil, List("g"), Nil, outDir, classesDir, None, None, None, None, None, None, None))
    bloop.config.write(configFileG, jsonTargetG)
    // format: ON

    configDir
  }

  def createTestServices(addDiagnosticsHandler: Boolean, logger0: BspClientLogger[_]): Services = {
    implicit val ctx: DebugFilter = DebugFilter.Bsp
    import ch.epfl.scala.bsp
    import ch.epfl.scala.bsp.endpoints
    val logger: bloop.logging.Logger = logger0
    val rawServices = Services
      .empty(logger0)
      .notification(endpoints.Build.showMessage) {
        case bsp.ShowMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
        case bsp.ShowMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
        case bsp.ShowMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
        case bsp.ShowMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
      }
      .notification(endpoints.Build.logMessage) {
        case bsp.LogMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
        case bsp.LogMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
        case bsp.LogMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
        case bsp.LogMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
      }

    // Lsp4s fails if we try to repeat a handler for a given notification
    if (!addDiagnosticsHandler) rawServices
    else {
      rawServices.notification(endpoints.Build.publishDiagnostics) {
        case bsp.PublishDiagnosticsParams(uri, _, _, diagnostics, _) =>
          // We prepend diagnostics so that tests can check they came from this notification
          def printDiagnostic(d: bsp.Diagnostic): String = s"[diagnostic] ${d.message} ${d.range}"
          diagnostics.foreach { d =>
            d.severity match {
              case Some(bsp.DiagnosticSeverity.Error) => logger.error(printDiagnostic(d))
              case Some(bsp.DiagnosticSeverity.Warning) => logger.warn(printDiagnostic(d))
              case Some(bsp.DiagnosticSeverity.Information) => logger.info(printDiagnostic(d))
              case Some(bsp.DiagnosticSeverity.Hint) => logger.debug(printDiagnostic(d))
              case None => logger.info(printDiagnostic(d))
            }
          }
      }
    }
  }

  case class ParsedFile(relativePath: RelativePath, contents: String)
  def parseFile(contents0: String): ParsedFile = {
    val contents = contents0.trim
    contents.split(System.lineSeparator()) match {
      case Array() =>
        sys.error(
          s"""Expected parsed file format:
             |
             |```
             |/relative/path/file.txt
             |contents of txt
             |file
             |```
             |
             |Obtained ${contents}
        """.stripMargin
        )
      case lines =>
        val potentialPath = lines.head
        if (!potentialPath.startsWith("/")) {
          sys.error("First line of contents file does not start with `/`")
        } else {
          val contents = lines.tail.mkString(System.lineSeparator)
          val relPath = potentialPath.replace("/", File.separator).stripPrefix(File.separator)
          ParsedFile(RelativePath(relPath), contents)
        }
    }
  }

  def loadStateFromProjects(baseDir: AbsolutePath, projects: List[TestProject]): State = {
    val configDir = TestProject.populateWorkspace(baseDir, projects)
    val logger = BloopLogger.default(configDir.toString())
    TestUtil.loadTestProject(configDir.underlying, logger, false, identity(_))
  }
}
