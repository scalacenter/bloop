package bloop.exec

import bloop.config.Config
import bloop.io.AbsolutePath
import javax.tools.ToolProvider

import scala.util.Try

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

  def fromConfig(jvm: Config.JvmConfig): JavaEnv = {
    val jvmHome = jvm.home.map(AbsolutePath.apply).getOrElse(JavaEnv.DefaultJavaHome)
    JavaEnv(jvmHome, jvm.options.toArray)
  }

  def toConfig(env: JavaEnv): Config.JvmConfig = {
    Config.JvmConfig(Some(env.javaHome.underlying), env.javaOptions.toList)
  }

  def runtimeEnvironment: Runtime = {
    Option(ToolProvider.getSystemJavaCompiler) match {
      case Some(_) => JDK
      case None => JRE
    }
  }

  def version: Option[String] = {
    Option(sys.props("java.version"))
  }

  def isDebugInterfaceEnabled: Boolean = {
    def loadTools(): Unit = {
      import java.net.URL
      import java.net.URLClassLoader

      Option(ToolProvider.getSystemToolClassLoader)
        .collect { case classLoader: URLClassLoader => classLoader.getURLs }
        .filter(_.nonEmpty)
        .foreach { urls =>
          val method = classOf[URLClassLoader].getDeclaredMethod("addURL", classOf[URL])
          method.setAccessible(true)
          urls.foreach(method.invoke(getClass.getClassLoader, _))
        }
    }

    val debugInterfaceResolution = Try(Class.forName("com.sun.jdi.Value")).recoverWith[Class[_]] {
      case _: ClassNotFoundException =>
        Try {
          loadTools()
          Class.forName("com.sun.jdi.Value")
        }
    }

    debugInterfaceResolution.isSuccess
  }

  /**
   * Default `JavaEnv` constructed from this JVM. Uses the same `javaHome`,
   * and specifies no arguments.
   */
  val default: JavaEnv = {
    val javaOptions = Array.empty[String]
    JavaEnv(DefaultJavaHome, javaOptions)
  }

  sealed trait Runtime
  case object JDK extends Runtime
  case object JRE extends Runtime
}
