package bloop.testing

import java.util.regex.Pattern

import bloop.DependencyResolution
import bloop.exec.{Fork, InProcess, JavaEnv, MultiplexedStreams, ProcessConfig}
import bloop.io.AbsolutePath
import bloop.logging.Logger
import sbt.testing.{
  AnnotatedFingerprint,
  EventHandler,
  Fingerprint,
  Framework,
  SubclassFingerprint,
  Task => TestTask
}
import org.scalatools.testing.{Framework => OldFramework}
import sbt.internal.inc.Analysis
import sbt.internal.inc.classpath.{FilteredLoader, IncludePackagesFilter}
import sbt.testing.{Framework, Task => TestTask, TaskDef}
import xsbt.api.Discovered
import xsbti.api.ClassLike
import xsbti.compile.CompileAnalysis

import scala.annotation.tailrec
import scala.collection.mutable

object TestInternals {

  private final val sbtOrg = "org.scala-sbt"
  private final val testAgentId = "test-agent"
  private final val testAgentVersion = "1.0.4"

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

  /**
   * Execute the test tasks.
   *
   * @param processConfig   Configures whether tests should be run in a forked JVM or in process.
   * @param discoveredTests The tests that were discovered.
   * @param eventHandler    Handler that reacts on messages from the testing frameworks.
   * @param logger          Logger receiving test output.
   */
  def executeTasks(processConfig: ProcessConfig,
                   discoveredTests: DiscoveredTests,
                   eventHandler: EventHandler,
                   logger: Logger): Unit = {
    processConfig match {
      case inProcess: InProcess => executeTasks(inProcess, discoveredTests, eventHandler, logger)
      case fork: Fork => executeTasks(fork, discoveredTests, eventHandler, logger)
    }
  }

  /**
   * Execute the test tasks in a forked JVM.
   *
   * @param fork            Configuration for the forked JVM.
   * @param discoveredTests The tests that were discovered.
   * @param eventHandler    Handler that reacts on messages from the testing frameworks.
   * @param logger          Logger receiving test output.
   */
  private def executeTasks(fork: Fork,
                           discoveredTests: DiscoveredTests,
                           eventHandler: EventHandler,
                           logger: Logger): Unit = {
    logger.debug("Starting forked test execution.")

    val testLoader = fork.toExecutionClassLoader(Some(filteredLoader))
    val server = new TestServer(logger, eventHandler, discoveredTests)
    val forkMain = classOf[sbt.ForkMain].getCanonicalName
    val arguments = Array(server.port.toString)
    val testAgentFiles = DependencyResolution.resolve(sbtOrg, testAgentId, testAgentVersion, logger)
    val testAgentJars = testAgentFiles.filter(_.underlying.toString.endsWith(".jar"))
    logger.debug("Test agent jars: " + testAgentFiles.mkString(", "))

    val exitCode = server.whileRunning {
      fork.runMain(forkMain, arguments, logger, testAgentJars)
    }

    if (exitCode != 0) logger.error(s"Forked execution terminated with non-zero code: $exitCode")
  }

  /**
   * Execute the test tasks in process.
   *
   * @param inProcess       The process configuration to run the tests
   * @param discoveredTests The tests that were discovered
   * @param eventHandler    Handler that reacts on messages from the testing frameworks.
   * @param logger          Logger receiving test output.
   */
  private def executeTasks(inProcess: InProcess,
                           discoveredTests: DiscoveredTests,
                           eventHandler: EventHandler,
                           logger: Logger): Unit = {
    @tailrec def loop(tasks: List[TestTask]): Unit = {
      tasks match {
        case task :: rest =>
          val newTasks = task.execute(eventHandler, Array(logger)).toList
          loop(rest ::: newTasks)

        case Nil =>
          ()
      }
    }

    MultiplexedStreams.withLoggerAsStreams(logger) {
      discoveredTests.tests.iterator.foreach {
        case (framework, taskDefs) =>
          val runner = getRunner(framework, discoveredTests.classLoader)
          val tasks = runner.tasks(taskDefs.toArray).toList
          loop(tasks)
          val summary = runner.done()
          if (summary.nonEmpty) logger.info(summary)
      }
    }
  }

  def getFramework(loader: ClassLoader,
                   classNames: List[String],
                   logger: Logger): Option[Framework] =
    classNames match {
      case head :: tail =>
        getFramework(loader, head, logger) orElse getFramework(loader, tail, logger)
      case Nil =>
        None
    }

  def getFingerprints(frameworks: Array[Framework])
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

  def getRunner(framework: Framework, testClassLoader: ClassLoader) = {
    framework.runner(Array.empty, Array.empty, testClassLoader)
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

  private def getFramework(loader: ClassLoader,
                           className: String,
                           logger: Logger): Option[Framework] = {
    try {
      Class.forName(className, true, loader).getDeclaredConstructor().newInstance() match {
        case framework: Framework =>
          Some(framework)
        case _: OldFramework =>
          logger.warn(s"Old frameworks are not supported: $className")
          None
      }
    } catch {
      case _: ClassNotFoundException => None
      case ex: Throwable =>
        logger.error(s"Couldn't initialize class $className:")
        logger.trace(ex)
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
