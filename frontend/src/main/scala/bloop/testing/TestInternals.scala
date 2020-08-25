package bloop.testing

import java.util.regex.Pattern

import bloop.DependencyResolution
import bloop.cli.{CommonOptions, ExitStatus}
import bloop.config.Config
import bloop.config.Config.TestArgument
import bloop.engine.ExecutionContext
import bloop.exec.{Forker, JvmProcessForker}
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import sbt.testing.{
  AnnotatedFingerprint,
  Event,
  Fingerprint,
  Framework,
  Runner,
  SubclassFingerprint,
  TaskDef
}
import org.scalatools.testing.{Framework => OldFramework}
import sbt.internal.inc.Analysis
import sbt.internal.inc.classpath.{FilteredLoader, IncludePackagesFilter}
import xsbt.api.Discovered
import xsbti.api.ClassLike
import xsbti.compile.CompileAnalysis

import scala.collection.mutable
import scala.util.control.NonFatal

object TestInternals {
  private final val sbtOrg = "org.scala-sbt"
  private final val testAgentId = "test-agent"
  private final val testAgentVersion = "1.2.8"

  private implicit val logContext: DebugFilter = DebugFilter.Test

  // Cache the resolution of test agent files since it's static (cannot be lazy because depends on logger)
  @volatile private var testAgentFiles: Option[Array[AbsolutePath]] = None

  private type PrintInfo[F <: Fingerprint] = (String, Boolean, Framework, F)

  lazy val filteredLoader = {
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
    val (exclusionFilters, inclusionFilters) = filters.partition(_.startsWith("-"))
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
        import bloop.engine.ExecutionContext.ioScheduler
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
   * @param userJvmOptions   The jvm options to run tests with.
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
      userJvmOptions: List[String],
      testEventHandler: TestSuiteEventHandler,
      logger: Logger,
      opts: CommonOptions
  ): Task[Int] = {
    logger.debug("Starting forked test execution...")

    // Make sure that we cache the resolution of the test agent JAR and we don't repeat it every time
    val agentFiles = lazyTestAgents(logger)

    val server = new TestServer(logger, testEventHandler, classLoader, discovered, args, opts)
    val forkMain = classOf[sbt.ForkMain].getCanonicalName
    val arguments = userJvmOptions.toArray ++ Array(server.port.toString)
    val testAgentJars = agentFiles.filter(_.underlying.toString.endsWith(".jar"))
    logger.debug("Test agent JARs: " + testAgentJars.mkString(", "))

    val cancelled: AtomicBoolean = AtomicBoolean(false)

    val listener = server.listenToTests
    val runner = {
      val runTask = forker.runMain(cwd, forkMain, arguments, false, logger, opts, testAgentJars)
      runTask.map(exitCode => Forker.exitStatus(exitCode).code)
    }

    val listenerHandle = listener.reporter.runAsync(ExecutionContext.ioScheduler)

    def cancel(): Unit = {
      if (!cancelled.getAndSet(true)) {
        listenerHandle.cancel()
      }
    }

    runner
      .delayExecutionWith(listener.startServer)
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
   * nuprocess instead of the default js environment.
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
  ): (List[PrintInfo[SubclassFingerprint]], List[PrintInfo[AnnotatedFingerprint]]) = {
    // The tests need to be run with the first matching framework, so we use a LinkedHashSet
    // to keep the ordering of `frameworks`.
    val subclasses = mutable.LinkedHashSet.empty[PrintInfo[SubclassFingerprint]]
    val annotated = mutable.LinkedHashSet.empty[PrintInfo[AnnotatedFingerprint]]
    for {
      framework <- frameworks
      fingerprint <- framework.fingerprints()
    } fingerprint match {
      case sub: SubclassFingerprint =>
        subclasses += ((sub.superclassName, sub.isModule, framework, sub))
      case ann: AnnotatedFingerprint =>
        annotated += ((ann.annotationName, ann.isModule, framework, ann))
    }
    (subclasses.toList, annotated.toList)
  }

  // Slightly adapted from sbt/sbt
  def matchingFingerprints(
      subclassPrints: List[PrintInfo[SubclassFingerprint]],
      annotatedPrints: List[PrintInfo[AnnotatedFingerprint]],
      d: Discovered
  ): List[PrintInfo[Fingerprint]] = {
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
      in: List[PrintInfo[T]],
      names: Set[String],
      IsModule: Boolean
  ): List[PrintInfo[T]] = {
    in collect { case info @ (name, IsModule, _, _) if names(name) => info }
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
        logger.report(s"Initialisation of test framework $fqn failed", t)
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
