package bloop.util

import javax.tools.ToolProvider
import scala.util.Try
import scala.util.Failure
import bloop.io.AbsolutePath
import javax.tools.JavaCompiler

sealed trait JavaRuntime
object JavaRuntime {
  case object JDK extends JavaRuntime
  case object JRE extends JavaRuntime

  val home: AbsolutePath = AbsolutePath(sys.props("java.home"))
  val version: String = sys.props("java.version")
  val javac: Option[AbsolutePath] = javacBinaryFromJavaHome(home)

  // Has to be a def, not a val, otherwise we get "loader constraint violation"
  def javaCompiler: Option[JavaCompiler] = Option(ToolProvider.getSystemJavaCompiler)

  /**
   * Detects the runtime of the running JDK instance.
   */
  def current: JavaRuntime = {
    javaCompiler match {
      case Some(_) => JDK
      case None => JRE
    }
  }

  /**
   * Points to the javac binary location.
   *
   * The javac binary can be derived from [[javaHome]]. However, the home might
   * point to different places in different operating systems. For example, in
   * Linux it can point to the home of the runtime instead of the full java
   * home. It's possible that `bin/javac` doesn't exist in the runtime home,
   * but instead in the full home where the JDK installation was done.
   * Therefore, if we don't find javac in the usual location, we go to the
   * parent of java home and attempt the search again. If nothin works, we just
   * return `None` and let the caller of this function handle this case
   * appropriately.
   */
  def javacBinaryFromJavaHome(home: AbsolutePath): Option[AbsolutePath] = {
    def toJavaBinary(home: AbsolutePath) = home.resolve("bin").resolve("javac")
    if (!home.exists) None
    else {
      Option(toJavaBinary(home))
        .filter(_.exists)
        .orElse(Option(toJavaBinary(home.getParent)))
        .filter(_.exists)
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
