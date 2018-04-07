package bloop.exec

import bloop.io.AbsolutePath

/**
 * The configuration of the Java environment for a given project.
 *
 * @param javaHome    Location of the java home. The `java` binary is expected to be found
 *                    in `$javaHome/bin/java`.
 * @param javaOptions The options to pass the JVM when starting.
 */
final case class JavaEnv(javaHome: AbsolutePath, javaOptions: Array[String])

object JavaEnv {

  private[bloop] final val DefaultJavaHome = AbsolutePath(sys.props("java.home"))

  /**
   * Default `JavaEnv` constructed from this JVM. Uses the same `javaHome`,
   * and specifies no arguments.
   */
  val default: JavaEnv = {
    val javaOptions = Array.empty[String]
    JavaEnv(DefaultJavaHome, javaOptions)
  }
}
