package bloop.testing

import bloop.DependencyResolution
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.Logger

object TestNGFrameworkDependency {
  private val testNGDepPattern = raw"testng-.*\.jar".r
  private val testNGFrameworkDepPattern = raw"mill-contrib-testng-.*\.jar".r

  def maybeAddTestNGFrameworkDependency(
      rawClasspath: List[AbsolutePath],
      logger: Logger
  ): List[AbsolutePath] = {
    var testngDependency = false
    var testNGFrameworkDependency = false
    rawClasspath.foreach { jar =>
      if (jar.isFile) {
        jar.underlying.getFileName().toString() match {
          case testNGDepPattern() =>
            testngDependency = true
          case testNGFrameworkDepPattern() =>
            testNGFrameworkDependency = true
          case _ =>
        }
      }
    }
    val resolved =
      if (testngDependency && !testNGFrameworkDependency) getTestNGFrameworkDependency(logger)
      else None
    resolved.map(rawClasspath ++ _).getOrElse(rawClasspath)
  }

  private def getTestNGFrameworkDependency(logger: Logger): Option[Array[AbsolutePath]] = {
    val testNGFramework =
      DependencyResolution.Artifact("com.lihaoyi", "mill-contrib-testng", BuildInfo.millVersion)
    DependencyResolution.resolveWithErrors(List(testNGFramework), logger) match {
      case Left(error) =>
        logger.warn(s"Failed to resolve TestNG framework dependency: $error")
        None
      case Right(value) => Some(value)
    }
  }
}
