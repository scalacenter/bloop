package bloop.testing

import bloop.Compiler
import bloop.cli.ExitStatus
import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.Paths.AttributedPath
import bloop.util.JavaCompat._
import bloop.util.{TestProject, TestUtil}
import bloop.reporter.Problem
import bloop.engine.ExecutionContext
import bloop.engine.caches.LastSuccessfulResult

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.experimental.macros
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import utest.TestSuite
import utest.Tests
import utest.asserts.Asserts
import utest.framework.Formatter
import utest.framework.TestCallTree
import utest.framework.Tree
import utest.ufansi.Attrs
import utest.ufansi.Str
import utest.ufansi.Color

import monix.eval.Task
import bloop.io.Paths
import monix.execution.misc.NonFatal
import bloop.logging.RecordingLogger
import bloop.logging.BspServerLogger

class BaseSuite extends TestSuite with BloopHelpers {
  val pprint = _root_.pprint.PPrinter.BlackWhite
  def isWindows: Boolean = bloop.util.CrossPlatform.isWindows
  def isAppveyor: Boolean = "True" == System.getenv("APPVEYOR")
  def beforeAll(): Unit = ()
  def afterAll(): Unit = ()
  def intercept[T: ClassTag](exprs: Unit): T = macro Asserts.interceptProxy[T]

  def assertNotEmpty(string: String): Unit = {
    if (string.isEmpty) {
      fail(
        s"expected non-empty string, obtained empty string."
      )
    }
  }

  def assertEmpty(string: String): Unit = {
    if (!string.isEmpty) {
      fail(
        s"expected empty string, obtained: $string"
      )
    }
  }

  def assertContains(string: String, substring: String): Unit = {
    assert(string.contains(substring))
  }

  def assertNotContains(string: String, substring: String): Unit = {
    assert(!string.contains(substring))
  }

  def assert(exprs: Boolean*): Unit = macro Asserts.assertProxy

  def assertNotEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained == expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> == expected=<$expected>$hintMsg")
    }
  }

  def assertEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained != expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> != expected=<$expected>$hintMsg")
    }
  }

  def assertNotFile(path: AbsolutePath): Unit = {
    if (path.isFile) {
      fail(s"file exists: $path", stackBump = 1)
    }
  }

  def assertIsFile(path: AbsolutePath): Unit = {
    if (!path.isFile) {
      fail(s"no such file: $path", stackBump = 1)
    }
  }

  def assertIsDirectory(path: AbsolutePath): Unit = {
    if (!path.isDirectory) {
      fail(s"directory doesn't exist: $path", stackBump = 1)
    }
  }

  def assertIsNotDirectory(path: AbsolutePath): Unit = {
    if (path.isDirectory) {
      fail(s"directory exists: $path", stackBump = 1)
    }
  }

  def assertExitStatus(obtainedState: TestState, expected: ExitStatus): Unit = {
    val obtained = obtainedState.status
    try assert(obtained == expected)
    catch {
      case NonFatal(t) =>
        obtainedState.state.logger match {
          case logger: RecordingLogger => logger.dump(); throw t
          case logger: BspServerLogger =>
            logger.underlying match {
              case logger: RecordingLogger => logger.dump(); throw t
              case _ => throw t
            }
          case _ => throw t
        }
    }
  }

  def assertSameResult(
      a: LastSuccessfulResult,
      b: LastSuccessfulResult
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assert(a.previous == b.previous)
    assert(a.classesDir == b.classesDir)
  }

  import bloop.logging.NoopLogger
  def takeDirectorySnapshot(
      dir: AbsolutePath
  )(implicit filename: sourcecode.File, line: sourcecode.Line): List[AttributedPath] = {
    import java.io.File
    val files = bloop.io.Paths
      .attributedPathFilesUnder(dir, "glob:**.*", NoopLogger)
      .map { ap =>
        val prefixPath = dir.syntax.stripSuffix("/")
        val osInsensitivePath = ap.path.syntax.replace(prefixPath, "").replace(File.separator, "/")
        val maskedRelativePath = AbsolutePath(osInsensitivePath)
        if (!maskedRelativePath.syntax.startsWith("/classes-")) {
          ap.copy(path = maskedRelativePath)
        } else {
          // Remove '/classes-*' from path
          val newPath = maskedRelativePath.syntax.split(File.separator).tail.tail.mkString("/")
          ap.copy(path = AbsolutePath("/" + newPath))
        }
      }

    files.sortBy(_.toString)
  }

  def assertDifferentExternalClassesDirs(
      s1: TestState,
      s2: TestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    projects.foreach { project =>
      assertDifferentExternalClassesDirs(s1, s2, project)
    }
  }

  def assertDifferentExternalClassesDirs(
      s1: TestState,
      s2: TestState,
      project: TestProject
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    try {
      assertSameExternalClassesDirs(s1, s2, project, printDiff = false)
      fail("External classes dirs of ${project.config.name} in both states is the same!")
    } catch {
      case e: DiffAssertions.TestFailedException => ()
    }
  }

  def assertSameExternalClassesDirs(
      s1: TestState,
      s2: TestState,
      project: TestProject,
      printDiff: Boolean = true
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val projectName = project.config.name
    val a = s1.client.getUniqueClassesDirFor(s1.build.getProjectFor(projectName).get)
    val b = s2.client.getUniqueClassesDirFor(s2.build.getProjectFor(projectName).get)
    val filesA = takeDirectorySnapshot(a)
    val filesB = takeDirectorySnapshot(b)
    assertNoDiff(
      pprint.apply(filesA, height = Int.MaxValue).render,
      pprint.apply(filesB, height = Int.MaxValue).render,
      print = printDiff
    )
  }

  def assertSameExternalClassesDirs(
      s1: TestState,
      s2: TestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    projects.foreach { project =>
      assertSameExternalClassesDirs(s1, s2, project)
    }
  }

  def assertSameFilesIn(
      a: AbsolutePath,
      b: AbsolutePath
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val filesA = takeDirectorySnapshot(a)
    val filesB = takeDirectorySnapshot(b)
    assertNoDiff(
      pprint.apply(filesA, height = Int.MaxValue).render,
      pprint.apply(filesB, height = Int.MaxValue).render,
      a.syntax,
      b.syntax
    )
  }

  def assertCancelledCompilation(state: TestState, projects: List[TestProject]): Unit = {
    projects.foreach { project =>
      state.getLastResultFor(project) match {
        case _: Compiler.Result.Cancelled => ()
        case result => fail(s"Result ${result} is not cancelled!")
      }
    }
  }

  def assertSuccessfulCompilation(
      state: TestState,
      projects: List[TestProject],
      isNoOp: Boolean
  ): Unit = {
    projects.foreach { project =>
      state.getLastResultFor(project) match {
        case s: Compiler.Result.Success => if (isNoOp) assert(s.isNoOp)
        case result => fail(s"Result ${result} is not cancelled!")
      }
    }
  }

  def assertDiagnosticsResult(
      result: Compiler.Result,
      errors: Int,
      warnings: Int = 0,
      expectFatalWarnings: Boolean = false
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    if (errors > 0) {
      result match {
        case Compiler.Result.Failed(problems, t, _, _) =>
          val count = Problem.count(problems)
          if (count.errors == 0 && errors != 0) {
            // If there's an exception count it as one error
            val errors = t match {
              case Some(_) => 1
              case None => 0
            }

            assert(count.errors == errors)
          } else {
            assert(count.errors == errors)
          }
          assert(count.warnings == warnings)
        case _ => fail(s"Result ${result} != Failed")
      }
    } else {
      result match {
        case Compiler.Result.Success(_, reporter, _, _, _, _, reportedFatalWarnings) =>
          val count = Problem.count(reporter.allProblemsPerPhase.toList)
          assert(count.errors == 0)
          assert(expectFatalWarnings == reportedFatalWarnings)
          assert(count.warnings == warnings)
        case _ => fail("Result ${result} != Success, but expected errors == 0")
      }
    }
  }

  def assertInvalidCompilationState(
      state: TestState,
      projects: List[TestProject],
      existsAnalysisFile: Boolean,
      hasPreviousSuccessful: Boolean,
      hasSameContentsInClassesDir: Boolean
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val buildProjects = projects.flatMap(p => state.build.getProjectFor(p.config.name).toList)
    assert(projects.size == buildProjects.size)
    projects.zip(buildProjects).foreach {
      case (testProject, buildProject) =>
        if (!existsAnalysisFile) assertNotFile(buildProject.analysisOut)
        else assertIsFile(buildProject.analysisOut)
        val latestResult = state.getLastSuccessfulResultFor(testProject)
        if (hasPreviousSuccessful) {
          val result = latestResult.get
          val analysis = result.previous.analysis().get()
          val stamps = analysis.readStamps
          assert(stamps.getAllProductStamps.asScala.nonEmpty)
          assert(stamps.getAllSourceStamps.asScala.nonEmpty)

          if (hasSameContentsInClassesDir) {
            val projectClassesDir = state.client.getUniqueClassesDirFor(buildProject)
            assert(takeDirectorySnapshot(result.classesDir).nonEmpty)
            assertSameFilesIn(projectClassesDir, result.classesDir)
          }
        } else {
          assert(latestResult.isEmpty)
          val lastResult = state.getLastResultFor(testProject)
          assert(lastResult != Compiler.Result.Empty)
          lastResult match {
            case Compiler.Result.NotOk(_) => ()
            case r => fail(s"Result $r is considered a success!")
          }
        }
    }
  }

  import bloop.io.RelativePath
  def assertCompileProduct(
      state: TestState,
      project: TestProject,
      classFile: RelativePath,
      existing: Boolean
  ): Unit = {
    val buildProject = state.build.getProjectFor(project.config.name).get
    val externalClassesDir = state.client.getUniqueClassesDirFor(buildProject)
    if (existing) assert(externalClassesDir.resolve(classFile).exists)
    else assert(!externalClassesDir.resolve(classFile).exists)
  }

  def assertNonExistingCompileProduct(
      state: TestState,
      project: TestProject,
      classFile: RelativePath
  ): Unit = {
    assertCompileProduct(state, project, classFile, false)
  }

  def assertExistingCompileProduct(
      state: TestState,
      project: TestProject,
      classFile: RelativePath
  ): Unit = {
    assertCompileProduct(state, project, classFile, true)
  }

  def assertExistingInternalClassesDir(lastState: TestState)(
      stateToCheck: TestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertExistenceOfInternalClassesDir(lastState, stateToCheck, projects, checkExists = true)
  }

  def assertNonExistingInternalClassesDir(lastState: TestState)(
      stateToCheck: TestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertExistenceOfInternalClassesDir(lastState, stateToCheck, projects, checkExists = false)
  }

  private def assertExistenceOfInternalClassesDir(
      lastState: TestState,
      stateToCheck: TestState,
      projects: List[TestProject],
      checkExists: Boolean
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val buildProjects =
      projects.flatMap(p => stateToCheck.build.getProjectFor(p.config.name).toList)
    assert(projects.size == buildProjects.size)
    projects.zip(buildProjects).foreach {
      case (testProject, buildProject) =>
        // Force the execution of the next last successful to delete the directory
        lastState.results.lastSuccessfulResult(buildProject).foreach { s =>
          import scala.concurrent.duration.FiniteDuration
          val _ = TestUtil.await(FiniteDuration(5, "s"))(s.populatingProducts)
        }

        val last = stateToCheck.getLastSuccessfulResultFor(testProject).get
        val classesDir = last.classesDir
        // Sleep for 20 ms to give time to classes dir to be deleted (runs inside `doOnFinish`)
        Thread.sleep(20)
        if (checkExists) assert(classesDir.exists)
        else assert(!classesDir.exists)
    }
  }

  def assertEmptyCompilationState(
      state: TestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val buildProjects = projects.flatMap(p => state.build.getProjectFor(p.config.name).toList)
    assert(projects.size == buildProjects.size)
    projects.zip(buildProjects).foreach {
      case (testProject, buildProject) =>
        assertNotFile(buildProject.analysisOut)
        assert(state.getLastSuccessfulResultFor(testProject).isEmpty)
        assert(state.getLastResultFor(testProject) == Compiler.Result.Empty)
        val classesDir = state.client.getUniqueClassesDirFor(buildProject)
        assert(takeDirectorySnapshot(classesDir).isEmpty)
    }
  }

  def assertValidCompilationState(
      state: TestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val buildProjects = projects.flatMap(p => state.build.getProjectFor(p.config.name).toList)
    assert(projects.size == buildProjects.size)
    projects.zip(buildProjects).foreach {
      case (testProject, buildProject) =>
        assertIsFile(buildProject.analysisOut)
        val latestResult = state
          .getLastSuccessfulResultFor(testProject)
          .getOrElse(
            sys.error(s"No latest result for ${testProject.config.name}, results: ${state.results}")
          )

        val analysis = latestResult.previous.analysis().get()
        val stamps = analysis.readStamps
        assert(stamps.getAllProductStamps.asScala.nonEmpty)
        assert(stamps.getAllSourceStamps.asScala.nonEmpty)
        //assert(stamps.getAllBinaryStamps.asScala.nonEmpty)

        assert(takeDirectorySnapshot(latestResult.classesDir).nonEmpty)
        val projectClassesDir = state.client.getUniqueClassesDirFor(buildProject)
        assertSameFilesIn(projectClassesDir, latestResult.classesDir)
    }
  }

  def assertNoDiff(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    colored {
      DiffAssertions.assertNoDiffOrPrintExpected(
        obtained,
        expected,
        obtainedTitle,
        expectedTitle,
        true
      )
      ()
    }
  }

  def assertNoDiff(
      obtained: String,
      expected: String,
      title: String = "",
      print: Boolean = true
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    colored {
      DiffAssertions.assertNoDiffOrPrintExpected(obtained, expected, title, title, print)
      ()
    }
  }

  def colored[T](
      thunk: => T
  )(implicit filename: sourcecode.File, line: sourcecode.Line): T = {
    try {
      thunk
    } catch {
      case scala.util.control.NonFatal(e) =>
        val message = e.getMessage.linesIterator
          .map { line =>
            if (line.startsWith("+")) Color.Green(line)
            else if (line.startsWith("-")) Color.LightRed(line)
            else Color.Reset(line)
          }
          .mkString("\n")
        val location = s"failed assertion at ${filename.value}:${line.value}\n"
        throw new DiffAssertions.TestFailedException(location + message)
    }
  }

  import java.nio.file.Files
  import java.nio.charset.StandardCharsets
  def readFile(path: AbsolutePath): String = {
    new String(Files.readAllBytes(path.underlying), StandardCharsets.UTF_8)
  }

  def writeFile(path: AbsolutePath, contents: String): AbsolutePath = {
    import scala.util.Try
    import java.nio.file.StandardOpenOption
    val body = Try(TestUtil.parseFile(contents)).map(_.contents).getOrElse(contents)

    // Running this piece in Windows can produce spurious `AccessDeniedException`s
    if (!bloop.util.CrossPlatform.isWindows) {
      // Delete the file, there are weird issues when creating new files and
      // SYNCING for existing files in macOS, so it's just better to remove this
      if (Files.exists(path.underlying)) {
        Files.delete(path.underlying)
      }

      AbsolutePath(
        Files.write(
          path.underlying,
          body.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.SYNC,
          StandardOpenOption.WRITE
        )
      )
    } else {
      AbsolutePath(
        Files.write(
          path.underlying,
          body.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.SYNC,
          StandardOpenOption.WRITE
        )
      )
    }

  }

  import monix.execution.CancelableFuture
  import java.util.concurrent.TimeUnit
  import scala.concurrent.duration.FiniteDuration
  def waitForDuration[T](future: CancelableFuture[T], duration: FiniteDuration)(
      ifError: => Unit
  ): T = {
    import java.util.concurrent.TimeoutException
    try Await.result(future, duration)
    catch {
      case t: TimeoutException => ifError; throw t
    }
  }

  def waitInSeconds[T](future: CancelableFuture[T], seconds: Int)(ifError: => Unit): T = {
    waitForDuration(future, FiniteDuration(seconds.toLong, TimeUnit.SECONDS))(ifError)
  }

  def waitInMillis[T](future: CancelableFuture[T], ms: Int)(ifError: => Unit): T = {
    waitForDuration(future, FiniteDuration(ms.toLong, TimeUnit.MILLISECONDS))(ifError)
  }

  def unsafeGetResource(resourceId: String): AbsolutePath = {
    val resource = this.getClass.getClassLoader().getResource(resourceId)
    if (resource == null) sys.error(s"Missing resource $resourceId")
    else AbsolutePath(resource.toURI())
  }

  override def utestAfterAll(): Unit = afterAll()
  override def utestFormatter: Formatter = new Formatter {
    override def exceptionMsgColor: Attrs = Attrs.Empty
    override def exceptionStackFrameHighlighter(
        s: StackTraceElement
    ): Boolean = {
      s.getClassName.startsWith("bloop.") &&
      !(s.getClassName.startsWith("bloop.util") ||
        s.getClassName.startsWith("bloop.testing"))
    }

    override def formatWrapWidth: Int = 3000
    override def formatException(x: Throwable, leftIndent: String): Str =
      super.formatException(x, "")
  }

  case class FlatTest(name: String, thunk: () => Unit)
  private val myTests = IndexedSeq.newBuilder[FlatTest]

  def ignore(name: String, label: String = "IGNORED")(fun: => Any): Unit = {
    myTests += FlatTest(
      utest.ufansi.Color.LightRed(s"$label - $name").toString(),
      () => ()
    )
  }

  def testOnlyOnJava8(name: String)(fun: => Any): Unit = {
    if (TestUtil.isJdk8) test(name)(fun)
    else ignore(name, label = s"IGNORED ON JAVA v${TestUtil.jdkVersion}")(fun)
  }

  def test(name: String)(fun: => Any): Unit = {
    myTests += FlatTest(name, () => { fun; () })
  }

  def testAsync(name: String, maxDuration: Duration = Duration("20s"))(
      run: => Unit
  ): Unit = {
    test(name) {
      Await.result(Task { run }.runAsync(ExecutionContext.scheduler), maxDuration)
    }
  }

  /*
  def testAsync(name: String, maxDuration: Duration = Duration("10min"))(
      run: => Future[Unit]
  ): Unit = {
    test(name) {
      val fut = run
      Await.result(fut, maxDuration)
    }
  }
   */

  def fail(msg: String, stackBump: Int = 0): Nothing = {
    val ex = new DiffAssertions.TestFailedException(msg)
    ex.setStackTrace(ex.getStackTrace.slice(1 + stackBump, 5 + stackBump))
    throw ex
  }

  override def tests: Tests = {
    val ts = myTests.result()
    val names = Tree("", ts.map(x => Tree(x.name)): _*)
    val thunks = new TestCallTree({
      this.beforeAll()
      Right(ts.map(x => new TestCallTree(Left(x.thunk()))))
    })
    Tests(names, thunks)
  }
}
