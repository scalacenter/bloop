package bloop.testing

import java.net.URL
import java.nio.file.Path
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import ch.epfl.scala.debugadapter.testing.TestSuiteEvent
import com.github.sbt.junit.jupiter.api.JupiterTestCollector

import bloop.DependencyResolution
import bloop.cli.CommonOptions
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.config.Config.TestArgument
import bloop.engine.ExecutionContext
import bloop.exec.Forker
import bloop.exec.JvmProcessForker
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task
import bloop.util.JavaRuntime

import monix.execution.atomic.AtomicBoolean
import org.scalatools.testing.{Framework => OldFramework}
import sbt.internal.inc.Analysis
import sbt.internal.inc.classpath.FilteredLoader
import sbt.internal.inc.classpath.IncludePackagesFilter
import sbt.testing.AnnotatedFingerprint
import sbt.testing.Event
import sbt.testing.Fingerprint
import sbt.testing.Framework
import sbt.testing.Runner
import sbt.testing.SubclassFingerprint
import sbt.testing.TaskDef
import xsbt.api.Discovered
import xsbti.api.ClassLike
import xsbti.compile.CompileAnalysis

final case class FingerprintInfo[+Print <: Fingerprint](
    name: String,
    isModule: Boolean,
    framework: Framework,
    fingerprint: Print
)

object TestInternals {
  private final val sbtOrg = "org.scala-sbt"
  private final val testAgentId = "test-agent"
  private final val testAgentVersion = "1.8.0"

  private implicit val logContext: DebugFilter = DebugFilter.Test

  // Cache the resolution of test agent files since it's static (cannot be lazy because depends on logger)
  @volatile private var testAgentFiles: Option[Array[AbsolutePath]] = None

  lazy val filteredLoader: FilteredLoader = {
    val filter = new IncludePackagesFilter(
      Set(
        "jdk.",
        "java.",
        "javax.",
        "sun.",
        "sbt.testing.",
        "org.scalatools.testing.",
        "org.xml.sax."
      )
    )
    new FilteredLoader(getClass.getClassLoader, filter)
  }

  /** Implementation class of the sbt test-interface adapter for JUnit 5 (Jupiter). */
  final val JupiterFrameworkClass = "com.github.sbt.junit.jupiter.api.JupiterFramework"

  /** Minimum Java version of the Bloop server JVM required to run JUnit 5 discovery. */
  private final val JUnit5MinJavaVersion = 17

  /**
   * Whether `framework` is the JUnit 5 (Jupiter) adapter. Its fingerprint reports a fake
   * annotation name on purpose, so fingerprint-based discovery never matches Jupiter tests;
   * they must be discovered with `discoverJUnit5Tests` instead.
   */
  def isJupiterFramework(framework: Framework): Boolean =
    framework.getClass.getName == JupiterFrameworkClass || framework.name() == "Jupiter"

  /**
   * Parses `filters` to produce a filtering function for the tests.
   * Only the tests accepted by this filter will be run.
   *
   * `*` is interpreter as wildcard. Each filter can start with `-`, in which case it means
   * that it is an exclusion filter.
   *
   * @param filters A list of strings, representing inclusion or exclusion patterns
   * @return A function that determines whether a test should be run given its FQCN.
   */
  def parseFilters(filters: List[String]): String => Boolean = {
    val (exclusionFilters, inclusionFilters) = filters.map(_.trim).partition(_.startsWith("-"))
    val inc = inclusionFilters.map(toPattern)
    val exc = exclusionFilters.map(f => toPattern(f.tail))

    (inc, exc) match {
      case (Nil, Nil) =>
        (_ => true)
      case (inc, Nil) =>
        (s => inc.exists(_.matcher(s).matches))
      case (Nil, exc) =>
        (s => !exc.exists(_.matcher(s).matches))
      case (inc, exc) =>
        (s => inc.exists(_.matcher(s).matches) && !exc.exists(_.matcher(s).matches))
    }
  }

  def lazyTestAgents(logger: Logger): Array[AbsolutePath] = {
    testAgentFiles match {
      case Some(paths) => paths
      case None =>
        val paths = DependencyResolution.resolve(
          List(DependencyResolution.Artifact(sbtOrg, testAgentId, testAgentVersion)),
          logger
        )
        testAgentFiles = Some(paths)
        paths
    }
  }

  /**
   * Execute the test tasks in a forked JVM.
   *
   * @param cwd              The directory in which to start the forked JVM
   * @param forker           Configuration for the forked JVM
   * @param classLoader      The class loader used for discovering the tests
   * @param discovered       The test tasks that were discovered, grouped by their `Framework`
   * @param args             The test arguments to pass to the framework
   * @param jvmOptions       The jvm options to run tests with.
   * @param testEventHandler Handler that reacts on messages from the testing frameworks
   * @param logger           Logger receiving test output
   * @param opts             The options to run the program with
   */
  def execute(
      cwd: AbsolutePath,
      forker: JvmProcessForker,
      classLoader: ClassLoader,
      discovered: Map[Framework, List[TaskDef]],
      args: List[Config.TestArgument],
      jvmOptions: List[String],
      envVariables: List[String],
      testEventHandler: BloopTestSuiteEventHandler,
      logger: Logger,
      opts: CommonOptions
  ): Task[Int] = {
    logger.debug("Starting forked test execution...")

    // Make sure that we cache the resolution of the test agent JAR and we don't repeat it every time
    val agentFiles = lazyTestAgents(logger)

    val server = new TestServer(logger, testEventHandler, classLoader, discovered, args, opts)
    val forkMain = classOf[sbt.ForkMain].getCanonicalName
    val arguments = Array(server.port.toString)
    val testAgentJars = agentFiles.filter(_.underlying.toString.endsWith(".jar"))
    logger.debug("Test agent JARs: " + testAgentJars.mkString(", "))

    val cancelled: AtomicBoolean = AtomicBoolean(false)

    val listener = server.listenToTests
    val runner = {
      val runTask =
        forker.runMain(
          cwd,
          forkMain,
          arguments,
          jvmOptions.toArray,
          envVariables,
          logger,
          opts,
          testAgentJars
        )
      runTask.map(exitCode => Forker.exitStatus(exitCode).code)
    }

    val listenerHandle = listener.reporter.runAsync(ExecutionContext.ioScheduler)

    def cancel(): Unit = {
      if (!cancelled.getAndSet(true)) {
        listenerHandle.cancel()
      }
    }

    listener.startServer *>
      runner
        .executeOn(ExecutionContext.ioScheduler)
        .doOnCancel(Task(cancel()))
  }

  /**
   * Execute the test tasks in process (without forking, unlike the JVM test server).
   *
   * This method is mainly useful for Scala.js, whose test adapter is already charged
   * with running the tests in an independent process (e.g. the Scala.js toolchain
   * implements the counterpart of our own `TestServer`). This design choice forces us
   * to duplicate some of our logic for testing.
   *
   * How these tests are run is something up to the `JsEnv` to decide. In bloop,
   * we provide our own implementation of the js environment so that we can use
   * forked process instead of the default js environment.
   *
   * The cancellation of this execution is controlled via the `dispose` method provided
   * by the discovered tests class. The dispose method is responsible for completely
   * closing any resource used by the Scala.js test framework.
   */
  private[bloop] def runJsTestsInProcess(
      discovered0: Map[Framework, List[TaskDef]],
      args: List[TestArgument],
      failureHandler: LoggingEventHandler,
      checkCancelled: () => Boolean,
      logger: Logger
  ): Task[Int] = Task {
    // Cancellation is delegated to the call-site
    val discovered = discovered0.toList.sortBy(_._1.name())
    discovered.foreach {
      case (framework, testSuites) =>
        // Only select those arguments that correspond to the framework we run
        val frameworkClass = framework.getClass.getName
        val fargs = args.filter { arg =>
          arg.framework match {
            case Some(f) => f.names.contains(frameworkClass)
            case None => true
          }
        }

        if (!checkCancelled()) {
          testSuites.foreach { testSuite =>
            if (!checkCancelled()) {
              val runner = getRunner(framework, fargs, null)
              val events0 = new mutable.ListBuffer[Event]
              val tasks = new mutable.ListBuffer[sbt.testing.Task]
              tasks ++= runner.tasks(Array(testSuite))

              while (tasks.nonEmpty) {
                val t = tasks.head
                tasks.remove(0)
                val next = t.execute(e => events0.append(e), Array(logger))
                tasks.prependAll(next)
              }

              val summary = runner.done()
              if (summary.nonEmpty) {
                // Scala.js runner seems to always add ansi color codes, remove
                // them here if logger doesn't support them (useful in bloop tests)
                val summaryToPrint =
                  if (logger.ansiCodesSupported()) summary
                  else summary.replaceAll("\u001B\\[[;\\d]*m", "")
                logger.info(s"Summary: $summaryToPrint")
              }
              val events = events0.toList

              failureHandler.handle(TestSuiteEvent.Results(testSuite.fullyQualifiedName(), events))
            }
          }
        }
    }

    ExitStatus.Ok.code
  }

  def loadFramework(l: ClassLoader, fqns: List[String], logger: Logger): Option[Framework] = {
    fqns match {
      case head :: tail => loadFramework(l, head, logger).orElse(loadFramework(l, tail, logger))
      case Nil => None
    }
  }

  def getFingerprints(
      frameworks: Seq[Framework]
  ): (List[FingerprintInfo[SubclassFingerprint]], List[FingerprintInfo[AnnotatedFingerprint]]) = {
    // The tests need to be run with the first matching framework, so we use a LinkedHashSet
    // to keep the ordering of `frameworks`.
    val subclasses = mutable.LinkedHashSet.empty[FingerprintInfo[SubclassFingerprint]]
    val annotated = mutable.LinkedHashSet.empty[FingerprintInfo[AnnotatedFingerprint]]
    for {
      framework <- frameworks
      fingerprint <- framework.fingerprints()
    } fingerprint match {
      case sub: SubclassFingerprint =>
        subclasses += FingerprintInfo(sub.superclassName, sub.isModule, framework, sub)
      case ann: AnnotatedFingerprint =>
        annotated += FingerprintInfo(ann.annotationName, ann.isModule, framework, ann)
    }
    (subclasses.toList, annotated.toList)
  }

  // Slightly adapted from sbt/sbt
  def matchingFingerprints(
      subclassPrints: List[FingerprintInfo[SubclassFingerprint]],
      annotatedPrints: List[FingerprintInfo[AnnotatedFingerprint]],
      d: Discovered
  ): List[FingerprintInfo[Fingerprint]] = {
    defined(subclassPrints, d.baseClasses, d.isModule) ++
      defined(annotatedPrints, d.annotations, d.isModule)
  }

  def getRunner(
      framework: Framework,
      args0: List[Config.TestArgument],
      testClassLoader: ClassLoader
  ): Runner = {
    val args = args0.toArray.flatMap(_.args)
    framework.runner(args, Array.empty, testClassLoader)
  }

  /** Jars that make up the JUnit Platform / Jupiter stack, which Bloop bundles itself. */
  private def isBundledJupiterJar(url: URL): Boolean = {
    val name = url.getPath.split('/').lastOption.getOrElse("")
    name.startsWith("junit-platform-") || name.startsWith("junit-jupiter-") ||
    name.startsWith("junit-vintage-") || name.startsWith("jupiter-interface-") ||
    name.startsWith("opentest4j-") || name.startsWith("apiguardian-api-")
  }

  /** Major version of the JVM Bloop itself is running on (handles both "17.0.x" and "1.8.0"). */
  private def currentJavaMajorVersion: Int = {
    val raw = JavaRuntime.version
    val normalized = if (raw.startsWith("1.")) raw.substring(2) else raw
    normalized.takeWhile(_.isDigit) match {
      case "" => 0
      case digits => digits.toInt
    }
  }

  /**
   * Discovers JUnit 5 (Jupiter) tests using the JUnit Platform launcher.
   *
   * Bloop's regular fingerprint discovery (via Zinc analysis) cannot find Jupiter tests: the
   * adapter reports a fake fingerprint on purpose and relies on its own discovery, which in sbt
   * lives in a plugin. We port that step here by running `JupiterTestCollector` against the
   * project's compiled classes, using Bloop's *bundled* JUnit Platform launcher and Jupiter
   * engine (Bloop is built against `jupiter-interface`).
   *
   * The project's own `junit-platform`/`junit-jupiter` jars are deliberately kept out of the
   * discovery classloader: if both Bloop's and the project's copies were visible, the platform's
   * ServiceLoader would see two copies of `TestEngine`/`JupiterTestEngine` and fail with
   * "TestEngine ... not a subtype" or duplicate-engine errors. The compiled test classes in
   * `classDirectory` are scanned with Bloop's engine; the tests themselves are run in the forked
   * JVM with the project's own engine.
   *
   * Bloop's bundled JUnit Platform launcher is compiled for Java 17, so discovery is skipped (with
   * a clear message) when the Bloop server runs on an older JVM.
   *
   * @param classDirectory   Directory that directly contains the project's compiled `.class` files.
   * @param runtimeClasspath Project runtime classpath URLs (Bloop's Jupiter jars are filtered out).
   * @param logger           Logger for diagnostic messages.
   * @return TaskDefs for the discovered Jupiter tests (empty if none, on failure, or on JDK < 17).
   */
  def discoverJUnit5Tests(
      classDirectory: Path,
      runtimeClasspath: Array[URL],
      logger: Logger
  ): List[TaskDef] = {
    if (currentJavaMajorVersion < JUnit5MinJavaVersion) {
      logger.warn(
        s"JUnit 5 test discovery requires the Bloop server to run on JDK $JUnit5MinJavaVersion+ " +
          s"(current: ${JavaRuntime.version}); skipping JUnit 5 discovery."
      )
      Nil
    } else {
      try {
        logger.debug(s"Discovering JUnit 5 tests in ${classDirectory.toAbsolutePath}")
        val discoveryClasspath = runtimeClasspath.filterNot(isBundledJupiterJar)
        val collector = new JupiterTestCollector.Builder()
          .withClassDirectory(classDirectory.toFile)
          .withClassLoader(getClass.getClassLoader)
          .withRuntimeClassPath(discoveryClasspath)
          .build()
        val discovered = collector.collectTests().getDiscoveredTests.asScala.toList
        logger.debug(s"Discovered ${discovered.size} JUnit 5 test(s)")
        discovered.map { item =>
          new TaskDef(
            item.getFullyQualifiedClassName,
            item.getFingerprint,
            item.isExplicit,
            item.getSelectors
          )
        }
      } catch {
        case e: LinkageError =>
          logger.error(
            s"JUnit 5 support requires the Bloop server on JDK $JUnit5MinJavaVersion+ " +
              s"(JUnit Platform 6 targets Java 17). Discovery skipped: ${e.getMessage}"
          )
          Nil
        case NonFatal(t) =>
          logger.error(s"JUnit 5 test discovery failed in ${classDirectory.toAbsolutePath}", t)
          Nil
      }
    }
  }

  /**
   * Filter all the `Definition`s from `analysis`, returning all the potential test suites.
   * Only top level `ClassLike`s are eligible as test suites. It is then the job of the test
   * frameworks to distinguish test suites from the rest.
   *
   * @param analysis The analysis containing all the definition
   * @return All the potential test suites found in `analysis`.
   */
  def potentialTests(analysis: CompileAnalysis): Seq[ClassLike] = {
    val all = allDefs(analysis)
    all.collect {
      case cl: ClassLike if cl.topLevel => cl
    }
  }

  // Taken from sbt/sbt, see Tests.scala
  private def allDefs(analysis: CompileAnalysis) = analysis match {
    case analysis: Analysis =>
      val acs: Seq[xsbti.api.AnalyzedClass] = analysis.apis.internal.values.toVector
      acs.flatMap { ac =>
        val companions = ac.api
        val all =
          Seq(companions.classApi, companions.objectApi) ++
            companions.classApi.structure.declared ++ companions.classApi.structure.inherited ++
            companions.objectApi.structure.declared ++ companions.objectApi.structure.inherited

        all
      }
  }

  // Slightly adapted from sbt/sbt
  private def defined[T <: Fingerprint](
      in: List[FingerprintInfo[T]],
      names: Set[String],
      IsModule: Boolean
  ): List[FingerprintInfo[T]] = {
    in.collect { case info @ FingerprintInfo(name, IsModule, _, _) if names(name) => info }
  }

  private def loadFramework(loader: ClassLoader, fqn: String, logger: Logger): Option[Framework] = {
    try {
      Class.forName(fqn, true, loader).getDeclaredConstructor().newInstance() match {
        case framework: Framework => Some(framework)
        case _: OldFramework => logger.warn(s"Old frameworks are not supported: $fqn"); None
      }
    } catch {
      case _: ClassNotFoundException => None
      case NonFatal(t) =>
        logger.error(s"Initialisation of test framework $fqn failed", t)
        None
    }
  }

  /**
   * Converts the input string to a compiled `Pattern`.
   *
   * The string is split at `*` (representing wildcards).
   *
   * @param filter The input filter
   * @return The compiled pattern matching the input filter.
   */
  private def toPattern(filter: String): Pattern = {
    val parts = filter.split("\\*", -1).map { // Don't discard trailing empty string, if any.
      case "" => ""
      case str => Pattern.quote(str)
    }
    Pattern.compile(parts.mkString(".*"))
  }

}
