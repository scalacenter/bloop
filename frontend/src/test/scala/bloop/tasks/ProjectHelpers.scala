package bloop.tasks

import java.io.IOException
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import bloop.cli.Commands
import bloop.engine.{Build, Interpreter, Run, State}
import bloop.exec.JavaEnv
import bloop.{Project, ScalaInstance}
import bloop.io.AbsolutePath
import bloop.internal.build.BuildInfo
import bloop.logging.{BloopLogger, ProcessLogger, RecordingLogger}

object ProjectHelpers {
  def projectDir(base: Path, name: String) = base.resolve(name)
  def sourcesDir(base: Path, name: String) = projectDir(base, name).resolve("src")
  def classesDir(base: Path, name: String) = projectDir(base, name).resolve("classes")

  def getProject(name: String, state: State): Project =
    state.build.getProjectFor(name).getOrElse(sys.error(s"Project '$name' does not exist!"))

  val RootProject = "target-project"
  def checkAfterCleanCompilation(
      structures: Map[String, Map[String, String]],
      dependencies: Map[String, Set[String]],
      rootProjectName: String = RootProject,
      scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance,
      javaEnv: JavaEnv = JavaEnv.default(fork = false),
      quiet: Boolean = false,
      failure: Boolean = false)(afterCompile: State => Unit = (_ => ())) = {
    withState(structures, dependencies, scalaInstance = scalaInstance, javaEnv = javaEnv) {
      (state: State) =>
        def action(state: State): Unit = {
          // Check that this is a clean compile!
          val projects = state.build.projects
          assert(projects.forall(p => noPreviousResult(p, state)))
          val project = getProject(rootProjectName, state)
          val action = Run(Commands.Compile(rootProjectName, incremental = true))
          val compiledState = Interpreter.execute(action, state)
          afterCompile(compiledState)
        }

        val logger = state.logger
        if (quiet) logger.quietIfSuccess(newLogger => action(state.copy(logger = newLogger)))
        else if (failure) logger.quietIfError(newLogger => action(state.copy(logger = newLogger)))
        else action(state)
    }
  }

  private final val integrationsIndexPath = BuildInfo.buildIntegrationsIndex.toPath
  private[bloop] lazy val testProjectsIndex: Map[String, Path] = {
    if (Files.exists(integrationsIndexPath)) {
      import scala.collection.JavaConverters._
      val lines = Files.readAllLines(integrationsIndexPath).asScala
      val entries = lines.map(line => line.split(",").toList)
      entries.map {
        case List(key, value) => key -> Paths.get(value)
        case _ => sys.error(s"Malformed index file: ${lines.mkString(System.lineSeparator)}")
      }.toMap
    } else sys.error(s"Missing integration index at ${integrationsIndexPath}!")
  }

  def getBloopConfigDir(name: String): Path = {
    testProjectsIndex
      .get(name)
      .getOrElse(sys.error(s"Project ${name} does not exist at ${integrationsIndexPath}"))
  }

  def loadTestProject(name: String): State = loadTestProject(getBloopConfigDir(name), name)

  def loadTestProject(configDir: Path, name: String): State = {
    val logger = BloopLogger.default(configDir.toString())
    assert(Files.exists(configDir), "Does not exist: " + configDir)

    val configDirectory = AbsolutePath(configDir)
    val loadedProjects = Project.fromDir(configDirectory, logger)
    val build = Build(configDirectory, loadedProjects)
    State.forTests(build, CompilationHelpers.getCompilerCache(logger), logger)
  }

  /**
   * Compile the given sources and then run `cmd`. Log messages are then given to `check`.
   *
   * @param sources The sources to compile.
   * @param fork    Whether the jave env should be forking.
   * @param cmd     The command to execute after compiling.
   * @param check   A function that'll receive the resulting log messages.
   */
  def runAndCheck(sources: Seq[String], fork: Boolean, cmd: Commands.CompilingCommand)(
      check: List[(String, String)] => Unit): Unit = {
    val noDependencies = Map.empty[String, Set[String]]
    val namedSources = sources.zipWithIndex.map { case (src, idx) => s"src$idx.scala" -> src }.toMap
    val projectsStructure = Map(cmd.project -> namedSources)
    val javaEnv = JavaEnv.default(fork)
    checkAfterCleanCompilation(projectsStructure,
                               noDependencies,
                               rootProjectName = cmd.project,
                               javaEnv = javaEnv,
                               quiet = true) { state =>
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
      check: List[(String, String)] => Unit): Unit = {
    val recordingLogger = new RecordingLogger
    val recordingStream = ProcessLogger.toOutputStream(recordingLogger.info _)
    val recordingState = state.copy(logger = recordingLogger)
    val project = getProject(cmd.project, recordingState)
    val _ = Interpreter.execute(Run(cmd), recordingState)
    check(recordingLogger.getMessages)
  }

  def withState[T](
      projectStructures: Map[String, Map[String, String]],
      dependencies: Map[String, Set[String]],
      scalaInstance: ScalaInstance,
      javaEnv: JavaEnv
  )(op: State => T): T = {
    withTemporaryDirectory { temp =>
      val projects = projectStructures.map {
        case (name, sources) =>
          val projectDependencies = dependencies.getOrElse(name, Set.empty)
          makeProject(temp, name, sources, projectDependencies, scalaInstance, javaEnv)
      }
      val logger = BloopLogger.default(temp.toString)
      val build = Build(AbsolutePath(temp), projects.toList)
      val state = State.forTests(build, CompilationHelpers.getCompilerCache(logger), logger)
      op(state)
    }
  }

  def noPreviousResult(project: Project, state: State): Boolean =
    !hasPreviousResult(project, state)
  def hasPreviousResult(project: Project, state: State): Boolean =
    state.results.getResult(project).analysis().isPresent

  def makeProject(baseDir: Path,
                  name: String,
                  sources: Map[String, String],
                  dependencies: Set[String],
                  scalaInstance: ScalaInstance,
                  javaEnv: JavaEnv): Project = {
    val baseDirectory = projectDir(baseDir, name)
    val (srcs, classes) = makeProjectStructure(baseDir, name)
    val tempDir = baseDirectory.resolve("tmp")
    Files.createDirectories(tempDir)

    val target = classesDir(baseDir, name)
    val depsTargets = (dependencies.map(classesDir(baseDir, _))).toArray.map(AbsolutePath.apply)
    val classpath = depsTargets ++ scalaInstance.allJars.map(AbsolutePath.apply)
    val sourceDirectories = Array(AbsolutePath(srcs))
    writeSources(srcs, sources)
    Project(
      name = name,
      baseDirectory = AbsolutePath(baseDirectory),
      dependencies = dependencies.toArray,
      scalaInstance = scalaInstance,
      rawClasspath = classpath,
      classesDir = AbsolutePath(target),
      scalacOptions = Array.empty,
      javacOptions = Array.empty,
      sourceDirectories = sourceDirectories,
      testFrameworks = Array.empty,
      javaEnv = javaEnv,
      tmp = AbsolutePath(tempDir),
      bloopConfigDir = AbsolutePath(baseDirectory) // This means nothing in tests
    )
  }

  def makeProjectStructure(base: Path, name: String): (Path, Path) = {
    val srcs = sourcesDir(base, name)
    val classes = classesDir(base, name)
    Files.createDirectories(srcs)
    Files.createDirectories(classes)
    (srcs, classes)
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
    finally delete(temp)
  }

  def withTemporaryFile[T](op: Path => T): T = {
    val temp = Files.createTempFile("tmp", "")
    try op(temp)
    finally delete(temp)
  }

  def delete(path: Path): Unit = {
    Files.walkFileTree(
      path,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      }
    )
    ()
  }
}
