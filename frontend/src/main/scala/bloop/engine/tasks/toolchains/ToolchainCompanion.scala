package bloop.engine.tasks.toolchains

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import bloop.DependencyResolution
import bloop.config.Config
import bloop.internal.build.BuildInfo
import bloop.logging.{DebugFilter, Logger}

/**
 * Base class for companion objects of toolchains.
 *
 * Caches instances and abstract over some common functionality.
 */
abstract class ToolchainCompanion[Toolchain] {

  /** The official name of the toolchain. */
  def name: String

  type Platform <: Config.Platform
  type Config <: Config.PlatformConfig

  case class PlatformData(name: String, toolchainClasspath: List[Path])

  /** The artifact name of this toolchain. */
  def artifactNameFrom(version: String): String

  /** The platform data if this toolchain must be resolved. */
  def getPlatformData(platform: Platform): Option[PlatformData]

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
  def resolveToolchain(platform: Platform, logger: Logger): Toolchain = {
    getPlatformData(platform) match {
      case None => apply(getClass.getClassLoader)
      case Some(PlatformData(name, toolchain)) =>
        if (toolchain.nonEmpty) toToolchain(toolchain)
        else instancesById.computeIfAbsent(name, a => toToolchain(resolveJars(a, logger)))
    }
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
    import bloop.engine.ExecutionContext.ioScheduler
    logger.debug(s"Resolving platform bridge: $BloopOrg:$artifactName:$BloopVersion")(
      DebugFilter.Compilation
    )
    val files = DependencyResolution.resolve(BloopOrg, artifactName, BloopVersion, logger)
    files.iterator.map(_.underlying).filter(_.toString.endsWith(".jar")).toList
  }

  private def toClassLoader(classpath: List[Path]): ClassLoader = {
    val parent = this.getClass.getClassLoader
    val entries = classpath.map(_.toUri.toURL).toArray
    new URLClassLoader(entries, parent)
  }
}
