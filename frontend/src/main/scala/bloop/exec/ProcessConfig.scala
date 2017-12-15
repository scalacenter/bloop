package bloop.exec

import java.io.File.{separator, pathSeparator}
import java.lang.ClassLoader
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

import bloop.io.AbsolutePath
import bloop.logging.{Logger, ProcessLogger}

/**
 * Configures how to start new processes to run Java code.
 */
sealed abstract class ProcessConfig {

  private var classLoaderCache: ConcurrentHashMap[Option[ClassLoader], ClassLoader] =
    new ConcurrentHashMap

  /**
   * The full classpath with which the code should be executed
   */
  def classpath: Array[AbsolutePath]

  /**
   * Creates a `ClassLoader` from the classpath of this `ProcessConfig`.
   *
   * @param parent A parent classloader
   * @return A classloader constructed from the classpath of this `ProcessConfig`.
   */
  final def toClassLoader(parent: Option[ClassLoader]): ClassLoader = {
    def makeNew(parent: Option[ClassLoader]): ClassLoader = {
      val classpathEntries = classpath.map(_.underlying.toUri.toURL)
      new URLClassLoader(classpathEntries, parent.orNull)
    }
    classLoaderCache.computeIfAbsent(parent, makeNew)
  }

  /**
   * Run the main function in class `className`, passing it `args`.
   *
   * @param className The fully qualified name of the class to run.
   * @param args      The arguments to pass to the main method.
   * @param logger    Where to log the messages from execution.
   * @return 0 if the execution exited successfully, a non-zero number otherwise.
   */
  def runMain(className: String, args: Array[String], logger: Logger): Int
}

object ProcessConfig {
  def apply(javaEnv: JavaEnv, classpath: Array[AbsolutePath]): ProcessConfig =
    if (javaEnv.fork) Fork(classpath, javaEnv)
    else InProcess(classpath)

  /** The code returned after a successful execution. */
  final val EXIT_OK = 0

  /** The code returned after the execution errored. */
  final val EXIT_ERROR = 1
}

/**
 * Configuration to execute Java code without starting a new JVM.
 */
case class InProcess(classpath: Array[AbsolutePath]) extends ProcessConfig {

  override def runMain(className: String, args: Array[String], logger: Logger): Int = {
    logger.debug(s"Running '$className' in process.")
    logger.debug(s"  Classpath: ${classpath.map(_.syntax).mkString(pathSeparator)}")
    val entries = classpath.map(_.toFile.toURI.toURL)
    val classLoader = new URLClassLoader(entries, null)
    val exitCode = {
      try {
        val clazz = classLoader.loadClass(className)
        val main = clazz.getMethod("main", classOf[Array[String]])
        val _ = MultiplexedStreams.withLoggerAsStreams(logger) {
          main.invoke(null, args)
        }
        logger.debug("In process run finished successfully.")
        ProcessConfig.EXIT_OK
      } catch {
        case NonFatal(ex) =>
          logger.trace(ex)
          ProcessConfig.EXIT_ERROR
      }
    }
    exitCode
  }
}

/**
 * Configuration to start a new JVM to execute Java code.
 */
case class Fork(classpath: Array[AbsolutePath], javaEnv: JavaEnv) extends ProcessConfig {
  override def runMain(className: String, args: Array[String], logger: Logger): Int = {
    val java = javaEnv.javaHome.resolve("bin").resolve("java")
    val classpathOption = "-cp" :: classpath.map(_.syntax).mkString(pathSeparator) :: Nil
    val appOptions = className :: args.toList
    val cmd = java.syntax :: javaEnv.javaOptions.toList ::: classpathOption ::: appOptions

    logger.debug(s"Running '$className' in a new JVM.")
    logger.debug(s"  java_home   = '${javaEnv.javaHome}'")
    logger.debug(s"  javaOptions = '${javaEnv.javaOptions.mkString(" ")}'")
    logger.debug(s"  classpath   = '${classpath.map(_.syntax).mkString(pathSeparator)}'")
    logger.debug(s"  command     = '${cmd.mkString(" ")}'")

    val processBuilder = new ProcessBuilder(cmd: _*)
    val process = processBuilder.start()
    val processLogger = new ProcessLogger(logger, process)
    processLogger.start()
    val exitCode = process.waitFor()

    exitCode
  }
}
