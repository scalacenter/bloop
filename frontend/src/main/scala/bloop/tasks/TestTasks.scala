package bloop.tasks

import java.net.URLClassLoader

import bloop.Project
import bloop.logging.Logger
import bloop.util.FilteredClassLoader
import sbt.testing.{Task => TestTask, _}
import org.scalatools.testing.{Framework => OldFramework}
import sbt.internal.inc.Analysis
import xsbt.api.{Discovered, Discovery}
import xsbti.compile.CompileAnalysis

import scala.annotation.tailrec
import scala.collection.mutable

class TestTasks(projects: Map[String, Project], logger: Logger) {

  private type PrintInfo[F <: Fingerprint] = (String, Boolean, Framework, F)

  def definedTests(projectName: String,
                   testLoader: ClassLoader): Seq[(() => Runner, Seq[TaskDef])] = {
    val project = projects(projectName)
    val frameworks = project.testFrameworks.flatMap(f => getFramework(testLoader, f.toList))
    logger.debug("Found frameworks: " + frameworks.map(_.name).mkString(", "))

    val (subclassPrints, annotatedPrints) = getFingerprints(frameworks)

    val analysis = project.previousResult.analysis().orElse(Analysis.empty)
    val definitions = allDefs(analysis)

    val discovered = Discovery(subclassPrints.map(_._1), annotatedPrints.map(_._1))(definitions)
    logger.debug("Discovered tests: " + discovered.map(_._1.name()).mkString(", "))

    val tasks = mutable.Map.empty[Framework, mutable.Buffer[TaskDef]]
    frameworks.foreach(tasks(_) = mutable.Buffer.empty)
    discovered.foreach {
      case (de, di) =>
        val printInfos = matchingFingerprints(subclassPrints, annotatedPrints, di)
        printInfos.foreach {
          case (_, _, framework, fingerprint) =>
            val taskDef = new TaskDef(de.name, fingerprint, false, Array(new SuiteSelector))
            tasks(framework) += taskDef
        }
    }

    tasks.toSeq.map {
      case (framework, taskDefs) => (() => getRunner(framework, testLoader)) -> taskDefs
    }
  }

  def runTests(runner: Runner, taskDefs: Array[TaskDef]): Unit = {
    val tasks = runner.tasks(taskDefs).toList
    executeTasks(tasks)
  }

  def getTestLoader(projectName: String): ClassLoader = {
    val project = projects(projectName)
    val entries = (project.classpath :+ project.classesDir).map(_.underlying.toUri.toURL)
    new URLClassLoader(entries, filteredLoader)
  }

  lazy val eventHandler = new EventHandler {
    override def handle(event: Event): Unit = ()
  }

  // Slightly adapted from sbt/sbt
  private def defined[T <: Fingerprint](in: Set[PrintInfo[T]],
                                        names: Set[String],
                                        IsModule: Boolean): Set[PrintInfo[T]] = {
    in collect { case info @ (name, IsModule, _, _) if names(name) => info }
  }

  // Slightly adapted from sbt/sbt
  private def matchingFingerprints(subclassPrints: Set[PrintInfo[SubclassFingerprint]],
                                   annotatedPrints: Set[PrintInfo[AnnotatedFingerprint]],
                                   d: Discovered): Set[PrintInfo[Fingerprint]] = {
    defined(subclassPrints, d.baseClasses, d.isModule) ++
      defined(annotatedPrints, d.annotations, d.isModule)
  }

  private def getRunner(framework: Framework, testClassLoader: ClassLoader) = {
    framework.runner(Array.empty, Array.empty, testClassLoader)
  }

  private def getFingerprints(frameworks: Array[Framework])
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

  private def getFramework(loader: ClassLoader, classNames: List[String]): Option[Framework] =
    classNames match {
      case head :: tail =>
        getFramework(loader, head) orElse getFramework(loader, tail)
      case Nil =>
        None
    }

  private def getFramework(loader: ClassLoader, className: String): Option[Framework] = {
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
    }
  }

  @tailrec
  private def executeTasks(tasks: List[TestTask]): Unit = {
    tasks match {
      case task :: rest =>
        val newTasks = task.execute(eventHandler, Array(logger)).toList
        executeTasks(rest ::: newTasks)
      case Nil =>
        ()
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

  private lazy val filteredLoader = {
    val allow = (className: String) =>
      className.startsWith("java") ||
        className.startsWith("sun.") ||
        className.startsWith("sbt.testing.") ||
        className.startsWith("org.scalatools.testing.")
    new FilteredClassLoader(allow, getClass.getClassLoader)
  }

}
