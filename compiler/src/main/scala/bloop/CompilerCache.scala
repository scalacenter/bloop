package bloop

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import sbt.internal.inc.ZincUtil
import xsbti.compile.{ClasspathOptions, Compilers}

class CompilerCache(componentProvider: ComponentProvider, scalaJarsTarget: Path) {

  private val cache =
    new ConcurrentHashMap[(String, String, String), Compilers]()

  def get(id: (String, String, String)): Compilers =
    cache.computeIfAbsent(id, newCompilers)

  private def newCompilers(id: (String, String, String)): Compilers =
    newCompilers(id._1, id._2, id._3)

  private def newCompilers(scalaOrganization: String,
                           scalaName: String,
                           scalaVersion: String): Compilers = {
    val scalaInstance =
      ScalaInstance(scalaOrganization, scalaName, scalaVersion)
    val classpathOptions = ClasspathOptions.of(true, false, false, true, false)
    val compiler = Compiler.getScalaCompiler(scalaInstance,
                                             classpathOptions,
                                             componentProvider,
                                             scalaJarsTarget)
    ZincUtil.compilers(scalaInstance, classpathOptions, None, compiler)
  }
}
