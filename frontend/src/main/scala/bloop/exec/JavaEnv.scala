package bloop.exec

import bloop.config.Config
import bloop.io.AbsolutePath
import javax.tools.ToolProvider

import scala.util.{Failure, Try}

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

  lazy val resolveDebugInterface: Try[Unit] = {
    def resolveDebugInterface(): Unit = {
      Class.forName("com.sun.jdi.Value")
      ()
    }

    def loadTools(): Try[Unit] = {
      import java.net.URL
      import java.net.URLClassLoader

      val urls = Option(ToolProvider.getSystemToolClassLoader).collect {
        case classLoader: URLClassLoader => classLoader.getURLs
      }

      urls match {
        case None =>
          Failure(new Exception("JDI implementation is not provided by the vendor"))

        case Some(urls) =>
          val hotLoadTools = Try {
            val method = classOf[URLClassLoader].getDeclaredMethod("addURL", classOf[URL])
            method.setAccessible(true)
            urls.foreach(method.invoke(getClass.getClassLoader, _))
            resolveDebugInterface()
          }

          hotLoadTools
            .recoverWith {
              case cause: ClassNotFoundException =>
                Failure(new Exception("JDI implementation is not on the classpath", cause))
              case cause: ReflectiveOperationException =>
                Failure(new Exception("Could not load tools due to: " + cause.getMessage, cause))
            }
      }
    }

    Try(resolveDebugInterface()).orElse(loadTools())
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
