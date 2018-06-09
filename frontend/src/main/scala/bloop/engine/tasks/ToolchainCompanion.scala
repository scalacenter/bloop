package bloop.engine.tasks

import bloop.{DependencyResolution, Project}
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.config.Config

import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Base class for companion objects of toolchains. This is used to provide
 * caching of instances.
 */
abstract class ToolchainCompanion[Toolchain] {

  private[this] var resolvedInstance: Toolchain = _
  private[this] val instancesCache: ConcurrentHashMap[Array[AbsolutePath], Toolchain] =
    new ConcurrentHashMap

  type Config <: Config.PlatformConfig

  /**
   * Get a new instance of this toolchain for `project`.
   *
   * @param project The project for which to instantiate the toolchain.
   * @param logger  A logger that will receive messages about toolchain instantiation.
   * @return An instance of the toolchain.
   */
  def forConfig(config: Config, logger: Logger): Toolchain

  /** The artifact name of this toolchain. */
  def toolchainArtifactName: String

  /**
   * Create a new instance of this toolchain, which will use the given `classLoader`.
   *
   * @param classLoader A classloader that contains the toolchain.
   * @return A new instance of this toolchain.
   */
  def apply(classLoader: ClassLoader): Toolchain

  /**
   * Resolve the toolchain and returns an instance. A cached instance is returned
   * if this toolchain has already been instantiated for this version.
   *
   * @param logger The logger that will receive message about resolution.
   * @return An instance of this toolchain, possibly cached.
   */
  def resolveToolchain(logger: Logger): Toolchain = synchronized {
    if (resolvedInstance == null) {
      val jars = bridgeJars(logger)
      resolvedInstance = direct(jars)
    }
    resolvedInstance
  }

  /**
   * Creates a new classloader from this classpath, and returns an instance of this
   * toolchain. If a toolchain has already been instantiated for this classpath, a cached
   * instance is returned.
   *
   * @param classpath The classpath with which to run this toolchain.
   * @return An instance of this toolchain, possibly cached.
   */
  def direct(classpath: Array[AbsolutePath]): Toolchain = {
    def createToolchain(classpath: Array[AbsolutePath]): Toolchain = {
      val classLoader = toClassLoader(classpath)
      apply(classLoader)
    }
    instancesCache.computeIfAbsent(classpath, createToolchain)
  }

  private def bridgeJars(logger: Logger): Array[AbsolutePath] = {
    val organization = bloop.internal.build.BuildInfo.organization
    val version = bloop.internal.build.BuildInfo.version
    logger.debug(s"Resolving bridge: $organization:$toolchainArtifactName:$version")
    val files = DependencyResolution.resolve(organization, toolchainArtifactName, version, logger)
    files.filter(_.underlying.toString.endsWith(".jar"))
  }

  private def toClassLoader(classpath: Array[AbsolutePath]): ClassLoader = {
    val parent = this.getClass.getClassLoader
    val entries = classpath.map(_.underlying.toUri.toURL)
    new URLClassLoader(entries, parent)
  }

}
