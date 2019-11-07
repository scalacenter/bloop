package bloop.util

import javax.tools.ToolProvider
import scala.util.Try
import scala.util.Failure
import bloop.io.AbsolutePath

sealed trait JavaRuntime
object JavaRuntime {
  case object JDK extends JavaRuntime
  case object JRE extends JavaRuntime

  val home: AbsolutePath = AbsolutePath(sys.props("java.home"))
  val version: String = sys.props("java.version")

  /**
   * Detects the runtime of the running JDK instance.
   */
  def current: JavaRuntime = {
    Option(ToolProvider.getSystemJavaCompiler) match {
      case Some(_) => JDK
      case None => JRE
    }
  }

  /**
   * Loads the java debug interface once.
   *
   * The Java Debug Interface is implemented in Java < 9 in an auxiliary jar
   * called `tools.jar`. The tools jar is only accessible in JDKs but it's not
   * loaded by default (users need to load it manually when they initialize an
   * application that requires it by setting up the classpath).
   *
   * To avoid our users from doing such a thing, we instead try to load the JDI
   * by finding the tools.jar dynamically on the java home of a JDK and then
   * side-effecting on the system classloader to attempt to load it.
   *
   * We only do this once and cache its result so that all debug clients know
   * whether debugging is supported or not in this bloop server instance.
   */
  lazy val loadJavaDebugInterface: Try[Unit] = {
    def initializeJDI() = { Class.forName("com.sun.jdi.Value"); () }
    def loadTools: Try[Unit] = {
      import java.net.URL
      import java.net.URLClassLoader

      val urls = Option(ToolProvider.getSystemToolClassLoader).collect {
        case classLoader: URLClassLoader => classLoader.getURLs
      }

      urls match {
        case None => Failure(new Exception("JDI implementation is not provided by the vendor"))

        case Some(urls) =>
          val hotLoadTools = Try {
            val method = classOf[URLClassLoader].getDeclaredMethod("addURL", classOf[URL])
            method.setAccessible(true)
            urls.foreach(method.invoke(getClass.getClassLoader, _))
            initializeJDI()
          }

          hotLoadTools.recoverWith {
            case cause: ClassNotFoundException =>
              Failure(new Exception("JDI implementation is not on the classpath", cause))
            case cause: ReflectiveOperationException =>
              Failure(new Exception("Could not load tools due to: " + cause.getMessage, cause))
          }
      }
    }

    Try(initializeJDI()).orElse(loadTools).map(_ => ())
  }
}
