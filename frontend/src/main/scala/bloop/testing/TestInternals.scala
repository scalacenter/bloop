package bloop.testing

import bloop.logging.Logger
import bloop.util.FilteredClassLoader
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
import xsbt.api.Discovered
import xsbti.compile.CompileAnalysis

import scala.annotation.tailrec
import scala.collection.mutable

object TestInternals {

  private type PrintInfo[F <: Fingerprint] = (String, Boolean, Framework, F)

  lazy val filteredLoader = {
    val allow = (className: String) =>
      className.startsWith("java") ||
        className.startsWith("sun.") ||
        className.startsWith("sbt.testing.") ||
        className.startsWith("org.scalatools.testing.")
    new FilteredClassLoader(allow, getClass.getClassLoader)
  }

  @tailrec
  def executeTasks(tasks: List[TestTask], eventHandler: EventHandler, logger: Logger): Unit = {
    tasks match {
      case task :: rest =>
        val newTasks = task.execute(eventHandler, Array(logger)).toList
        executeTasks(rest ::: newTasks, eventHandler, logger)
      case Nil =>
        ()
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

  // Taken from sbt/sbt, see Tests.scala
  def allDefs(analysis: CompileAnalysis) = analysis match {
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

}
