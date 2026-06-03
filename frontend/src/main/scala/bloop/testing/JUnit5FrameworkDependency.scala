package bloop.testing

import bloop.DependencyResolution
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger

/**
 * JUnit 5 needs the sbt test-interface adapter (`com.github.sbt.junit:jupiter-interface`,
 * which also provides the `JupiterFramework` and the JUnit Platform launcher) on the test
 * classpath to discover and run tests. Projects depending on `junit-jupiter` directly (e.g.
 * Maven/Gradle builds) usually don't have that adapter, so Bloop adds it here.
 */
object JUnit5FrameworkDependency {
  private val junitJupiterPattern = raw"junit-jupiter-.*\.jar".r
  private val jupiterInterfacePattern = raw"jupiter-interface-.*\.jar".r

  /**
   * If the project uses JUnit 5 (`junit-jupiter-*` on the classpath) but the adapter
   * (`jupiter-interface-*`) is missing, resolve it and append the parts that aren't already on
   * the classpath. The user's own `junit-jupiter` engine/api versions are left untouched so they
   * stay authoritative; only the adapter (and the platform launcher, if absent) are added.
   */
  def maybeAddJUnit5FrameworkDependency(
      rawClasspath: List[AbsolutePath],
      logger: Logger
  ): List[AbsolutePath] = {
    var junitJupiterDependency = false
    var jupiterInterfaceDependency = false
    rawClasspath.foreach { jar =>
      if (jar.isFile) {
        jar.underlying.getFileName().toString() match {
          case junitJupiterPattern() => junitJupiterDependency = true
          case jupiterInterfacePattern() => jupiterInterfaceDependency = true
          case _ =>
        }
      }
    }

    if (junitJupiterDependency && !jupiterInterfaceDependency) {
      getJUnit5FrameworkDependency(logger) match {
        case None => rawClasspath
        case Some(resolved) =>
          val present =
            rawClasspath.iterator
              .map(p => artifactBaseName(p.underlying.getFileName.toString))
              .toSet
          val additions =
            resolved.filterNot(p => present(artifactBaseName(p.underlying.getFileName.toString)))
          logger.debug(
            s"Adding JUnit 5 adapter jars: ${additions.map(_.underlying.getFileName).mkString(", ")}"
          )(DebugFilter.Test)
          val hasLauncher =
            present("junit-platform-launcher") ||
              additions.exists(
                _.underlying.getFileName.toString.startsWith("junit-platform-launcher")
              )
          if (!hasLauncher)
            logger.warn(
              "JUnit 5 detected but no junit-platform-launcher on the classpath; tests may not run."
            )
          rawClasspath ++ additions
      }
    } else rawClasspath
  }

  /** Strip the trailing `-<version>` from a jar file name, e.g. `jupiter-interface-0.19.0.jar`. */
  private def artifactBaseName(jarFileName: String): String =
    jarFileName
      .stripSuffix(".jar")
      .split('-')
      .reverse
      .dropWhile(_.headOption.exists(_.isDigit))
      .reverse
      .mkString("-")

  private def getJUnit5FrameworkDependency(logger: Logger): Option[Array[AbsolutePath]] = {
    val jupiterInterface =
      DependencyResolution.Artifact(
        "com.github.sbt.junit",
        "jupiter-interface",
        BuildInfo.jupiterInterfaceVersion
      )
    DependencyResolution.resolveWithErrors(List(jupiterInterface), logger) match {
      case Left(error) =>
        logger.warn(s"Failed to resolve JUnit 5 jupiter-interface dependency: $error")
        None
      case Right(value) => Some(value)
    }
  }
}
