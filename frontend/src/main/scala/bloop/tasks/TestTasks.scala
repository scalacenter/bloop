package bloop.tasks

import java.net.URLClassLoader

import bloop.Project
import bloop.logging.Logger
import bloop.util.FilteredClassLoader
import sbt.testing.{AnnotatedFingerprint, Framework, SubclassFingerprint}
import org.scalatools.testing.{Framework => OldFramework}
import sbt.internal.inc.Analysis
import xsbt.api.Discovery
import xsbti.compile.CompileAnalysis

import scala.collection.mutable

class TestTasks(projects: Map[String, Project], logger: Logger) {

  def definedTests(projectName: String): Unit = {
    val project = projects(projectName)
    val thisLoader = new FilteredClassLoader(
      name =>
        name.startsWith("java.") || name.startsWith("sbt.testing.") || name.startsWith(
          "org.scalatools.testing."),
      getClass.getClassLoader)
    val loader = getClassLoader(project, thisLoader)
    val frameworks =
      project.testFrameworks.flatMap(frameworks => getFramework(loader, frameworks.toList))
    logger.debug("Found frameworks: " + frameworks.mkString(", "))

    val definitions = allDefs(project.previousResult.analysis().orElse(Analysis.empty))
    val fingerprints = frameworks.flatMap(_.fingerprints())

    val subclasses = mutable.Set.empty[SubclassFingerprint]
    val annotated = mutable.Set.empty[AnnotatedFingerprint]
    fingerprints.foreach {
      case sub: SubclassFingerprint => subclasses += sub
      case ann: AnnotatedFingerprint => annotated += ann
    }
    val discovered =
      Discovery.apply(subclasses.map(_.superclassName()).toSet,
                      annotated.map(_.annotationName()).toSet)(definitions)
    logger.debug("Discovered tests: " + discovered)
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

  private def getClassLoader(project: Project, parent: ClassLoader): ClassLoader = {
    new URLClassLoader(project.classpath.map(_.underlying.toFile.toURI.toURL), parent)
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
      }.toSeq
  }

}
