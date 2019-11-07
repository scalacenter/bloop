package bloop.data

import bloop.config.Config
import bloop.util.JavaRuntime
import bloop.io.AbsolutePath

import scala.util.{Failure, Try}

/**
 * The configuration of a JDK for a concrete project. It can be used for either
 * compiling a project (`javac` config) or running an application or tests (jdk config).
 *
 * @param javaHome The location, we expect to find `java` in `$javaHome/bin/java`.
 * @param javaOptions JDK-specific options to pass at start-up.
 */
final case class JdkConfig(javaHome: AbsolutePath, javaOptions: Array[String])

object JdkConfig {
  val default: JdkConfig = JdkConfig(JavaRuntime.home, Array.empty)

  def fromConfig(jvm: Config.JvmConfig): JdkConfig = {
    val jvmHome = jvm.home.map(AbsolutePath.apply).getOrElse(default.javaHome)
    JdkConfig(jvmHome, jvm.options.toArray)
  }

  def toConfig(config: JdkConfig): Config.JvmConfig = {
    Config.JvmConfig(Some(config.javaHome.underlying), config.javaOptions.toList)
  }
}
