package bloop.engine.tasks

import java.net.URLClassLoader

import coursier.core.Repository

import bloop.DependencyResolution
import bloop.io.AbsolutePath
import bloop.logging.Logger

object BridgeClassLoader {

  def classLoader(logger: Logger,
                  artifact: String,
                  additionalRepositories: Seq[Repository] = List()): ClassLoader = {
    val jars = resolveBridge(logger, artifact, additionalRepositories)
    val entries = jars.map(_.underlying.toUri.toURL)
    new URLClassLoader(entries, getClass.getClassLoader)
  }

  /** @return Paths to JAR files */
  private def resolveBridge(logger: Logger,
                            artifact: String,
                            additionalRepositories: Seq[Repository]): Array[AbsolutePath] = {
    val organization = bloop.internal.build.BuildInfo.organization
    val version = bloop.internal.build.BuildInfo.version
    val files =
      DependencyResolution.resolve(organization, artifact, version, logger, additionalRepositories)

    files.filter(_.underlying.toString.endsWith(".jar"))
  }

}
