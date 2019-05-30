package bloop.dap

import java.net.{URL, URLClassLoader}

import bloop.exec.JavaEnv
import bloop.io.{AbsolutePath, RelativePath}

import scala.util.{Failure, Success, Try}

object JavaDebugInterface {

  /**
   * Checks if the Java Debug Interface is either already on classpath or if it can be automatically loaded.
   */
  def isAvailable: Try[_] = {
    def loadJdi: Try[_] = Try(Class.forName("com.sun.jdi.Value"))

    def addToClasspath(jar: AbsolutePath): Unit = {
      Try {
        val method = classOf[URLClassLoader].getDeclaredMethod("addURL", classOf[URL])
        method.setAccessible(true)
        method.invoke(getClass.getClassLoader, jar.toBspUri.toURL)
      }.recoverWith {
        case _: ReflectiveOperationException =>
          val error = s"$jar cannot be automatically added to classpath. Please do it manually."
          throw new IllegalStateException(error)
      }
    }

    loadJdi.recoverWith {
      case _: ClassNotFoundException =>
        JvmTools.jar
          .flatMap(jar => Try(addToClasspath(jar)))
          .flatMap(_ => loadJdi)
    }
  }

  private object JvmTools {
    private val tools = RelativePath("lib/tools.jar")

    private val javaHome = JavaEnv.DefaultJavaHome
    private val jdkPath = javaHome.resolve(tools)
    private val jrePath = javaHome.getParent.resolve(tools)
    private val windowsJrePath = {
      val version = javaHome.underlying.getFileName.toString.drop("jre".length)
      javaHome.getParent.resolve("jdk" + version).resolve(tools)
    }

    val jar: Try[AbsolutePath] = {
      val paths = List(jdkPath, jrePath, windowsJrePath)
      paths.find(_.exists) match {
        case None =>
          val error = s"Could not find 'tools.jar'. Searched in: $paths"
          Failure(new IllegalStateException(error))
        case Some(value) =>
          Success(value)
      }
    }
  }
}
