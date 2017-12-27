package bloop.exec

import bloop.io.AbsolutePath

/**
 * The configuration of the Java environment for a given project.
 * Defines whether we should fork, and how to spawn a new JVM.
 *
 * @param fork        If true, fork a new JVM. Run in process otherwise.
 * @param javaHome    Location of the java home. The `java` binary is expected to be found
 *                    in `$javaHome/bin/java`.
 * @param javaOptions The options to pass the JVM when starting.
 */
final case class JavaEnv(fork: Boolean, javaHome: AbsolutePath, javaOptions: Array[String])

object JavaEnv {

  /**
   * Default `JavaEnv` constructed from this JVM. Uses the same `javaHome`,
   * and specifies no arguments.
   *
   * @param fork If true, for ka new JVM. Run in process otherwise.
   * @return A `JavaEnv` configured from this JVM.
   */
  def default(fork: Boolean): JavaEnv = {
    val javaHome = AbsolutePath(sys.props("java.home"))
    val javaOptions = Array.empty[String]
    JavaEnv(fork, javaHome, javaOptions)
  }
}
