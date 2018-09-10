package bloop.engine.tasks

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import bloop.DependencyResolution
import bloop.config.Config
import bloop.internal.build.BuildInfo
import bloop.logging.Logger

/**
 * Base class for companion objects of toolchains.
 *
 * Caches instances and abstract over some common functionality.
 */
abstract class ToolchainCompanion[Toolchain] {
  /** The official name of the toolchain. */
  def name: String

  type Platform <: Config.Platform

  /** The artifact name of this toolchain. */
  def artifactNameFrom(version: String): String

  /**
   * Create a new instance of this toolchain, which will use the given `classLoader`.
   *
   * @param classLoader A classloader that contains the toolchain.
   * @return A new instance of this toolchain.
   */
  def apply(classLoader: ClassLoader): Toolchain

  private[this] val instancesById = new ConcurrentHashMap[String, Toolchain]
  private[this] val instancesByJar: ConcurrentHashMap[List[Path], Toolchain] = new ConcurrentHashMap

  /**
   * Returns a toolchain instance resolving it if necessary.
   *
   * @param logger The logger that will receive message about resolution.
   * @return An instance of this toolchain, possibly cached.
   */
  def resolveToolchain(platform: Config.Platform, logger: Logger): Toolchain = {
    val (artifactName, toolchain) = platform match {
      case Config.Platform.Js(config, _) => (artifactNameFrom(config.version), config.toolchain)
      case Config.Platform.Native(config, _) => (artifactNameFrom(config.version), config.toolchain)
      case Config.Platform.Jvm(_, _) =>
        throw new IllegalArgumentException("Fatal programming error: JVM toolchain does not exist.")
    }

    if (toolchain.nonEmpty) toToolchain(toolchain)
    else instancesById.computeIfAbsent(artifactName, a => toToolchain(resolveJars(a, logger)))
  }

  /**
   * Returns a (cached) classload of a toolchain instance from a list of jars.
   *
   * @param classpath The classpath with which to run this toolchain.
   * @return An instance of this toolchain, possibly cached.
   */
  private def toToolchain(classpath: List[Path]): Toolchain = {
    def createToolchain(classpath: List[Path]): Toolchain =
      apply(toClassLoader(classpath))

    instancesByJar.computeIfAbsent(classpath, createToolchain)
  }

  private final val BloopVersion = BuildInfo.version
  private final val BloopOrg = BuildInfo.organization
  private def resolveJars(artifactName: String, logger: Logger): List[Path] = {
    logger.debug(s"Resolving platform bridge: $BloopOrg:$artifactName:$BloopVersion")
    val files = DependencyResolution.resolve(BloopOrg, artifactName, BloopVersion, logger)
    files.iterator.map(_.underlying).filter(_.toString.endsWith(".jar")).toList
  }

  private def toClassLoader(classpath: List[Path]): ClassLoader = {
    val parent = this.getClass.getClassLoader
    val entries = classpath.map(_.toUri.toURL).toArray
    new URLClassLoader(entries, parent)
  }
}
