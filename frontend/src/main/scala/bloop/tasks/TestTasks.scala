package bloop.tasks

import java.net.URLClassLoader

import bloop.Project
import bloop.logging.Logger
import bloop.util.FilteredClassLoader
import sbt.testing.Framework
import org.scalatools.testing.{Framework => OldFramework}

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

}
