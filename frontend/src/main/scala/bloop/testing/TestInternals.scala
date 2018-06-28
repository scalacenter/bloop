package bloop.testing

import java.util.regex.Pattern

import bloop.DependencyResolution
import bloop.cli.CommonOptions
import bloop.config.Config
import bloop.engine.ExecutionContext
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import monix.eval.Task
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
  private final val testAgentVersion = "1.0.4"

  private implicit val logContext: DebugFilter = DebugFilter.Test

  // Cache the resolution of test agent files since it's static (cannot be lazy because depends on logger)
  @volatile private var testAgentFiles: Option[Array[AbsolutePath]] = None

  private type PrintInfo[F <: Fingerprint] = (String, Boolean, Framework, F)

  lazy val filteredLoader = {
    val filter = new IncludePackagesFilter(
      Set("java.", "javax.", "sun.", "sbt.testing.", "org.scalatools.testing.", "org.xml.sax."))
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
        val paths = DependencyResolution.resolve(sbtOrg, testAgentId, testAgentVersion, logger)
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
   * @param testEventHandler Handler that reacts on messages from the testing frameworks
   * @param logger           Logger receiving test output
   * @param opts             The options to run the program with
   */
  def execute(
      cwd: AbsolutePath,
      forker: Forker,
      classLoader: ClassLoader,
      discovered: Map[Framework, List[TaskDef]],
      args: List[Config.TestArgument],
      testEventHandler: TestSuiteEventHandler,
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

    val listener = server.listenToTests
    val runner = forker.runMain(cwd, forkMain, arguments, logger, opts, testAgentJars)
    val listenerHandle = listener.reporter.runAsync(ExecutionContext.ioScheduler)
    runner
      .delayExecutionWith(listener.startServer)
      .executeOn(ExecutionContext.ioScheduler)
      .doOnCancel(Task(listenerHandle.cancel()))
  }

  /**
   * Execute the test tasks without forking
   *
   * Used for Scala.js which already runs the tests in an external process
   */
  def executeWithoutForking(framework: Framework,
                            taskDefs: Array[TaskDef],
                            args: List[Config.TestArgument],
                            logger: Logger): List[Event] = {
    val runner = getRunner(framework, args, null)
    val events = new mutable.ListBuffer[Event]

    val tasks = new mutable.ListBuffer[sbt.testing.Task]
    tasks ++= runner.tasks(taskDefs)

    while (tasks.nonEmpty) {
      val t = tasks.head
      tasks.remove(0)
      val next = t.execute(e => events.append(e), Array(logger))
      tasks.prependAll(next)
    }

    events.toList
  }

  def loadFramework(l: ClassLoader, fqns: List[String], logger: Logger): Option[Framework] = {
    fqns match {
      case head :: tail => loadFramework(l, head, logger).orElse(loadFramework(l, tail, logger))
      case Nil => None
    }
  }

  def getFingerprints(frameworks: Seq[Framework])
    : (Set[PrintInfo[SubclassFingerprint]], Set[PrintInfo[AnnotatedFingerprint]]) = {
    val subclasses = mutable.Set.empty[PrintInfo[SubclassFingerprint]]
    val annotated = mutable.Set.empty[PrintInfo[AnnotatedFingerprint]]
    for {
      framework <- frameworks
      fingerprint <- framework.fingerprints()
    } fingerprint match {
      case sub: SubclassFingerprint =>
        subclasses += ((sub.superclassName, sub.isModule, framework, sub))
      case ann: AnnotatedFingerprint =>
        annotated += ((ann.annotationName, ann.isModule, framework, ann))
    }
    (subclasses.toSet, annotated.toSet)
  }

  // Slightly adapted from sbt/sbt
  def matchingFingerprints(subclassPrints: Set[PrintInfo[SubclassFingerprint]],
                           annotatedPrints: Set[PrintInfo[AnnotatedFingerprint]],
                           d: Discovered): Set[PrintInfo[Fingerprint]] = {
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
  private def defined[T <: Fingerprint](in: Set[PrintInfo[T]],
                                        names: Set[String],
                                        IsModule: Boolean): Set[PrintInfo[T]] = {
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
