package bloop

import java.nio.file.{Path, Paths}
import java.util.concurrent.ConcurrentHashMap

import sbt.internal.inc.{AnalyzingCompiler, ZincUtil}
import xsbti.Logger
import xsbti.compile.{ClasspathOptions, Compilers}

class CompilerCache(componentProvider: ComponentProvider, scalaJarsTarget: Path) {
  import CompilerCache.CacheId
  private val logger = QuietLogger
  private val cache  = new ConcurrentHashMap[CacheId, Compilers]()

  def get(id: CacheId): Compilers = cache.computeIfAbsent(id, newCompilers)

  private def newCompilers(cacheId: CacheId): Compilers = {
    val scalaInstance =
      ScalaInstance(cacheId.scalaOrganization, cacheId.scalaName, cacheId.scalaVersion)
    val classpathOptions = ClasspathOptions.of(true, false, false, true, false)
    val compiler =
      getScalaCompiler(scalaInstance, classpathOptions, componentProvider, scalaJarsTarget)
    ZincUtil.compilers(scalaInstance, classpathOptions, None, compiler)
  }

  private val home = System.getProperty("user.home")
  def getScalaCompiler(scalaInstance: ScalaInstance,
                       classpathOptions: ClasspathOptions,
                       componentProvider: ComponentProvider,
                       scalaJarsTarget: Path): AnalyzingCompiler = {
    componentProvider.component(bridgeComponentID(scalaInstance.version)) match {
      case Array(jar) =>
        ZincUtil.scalaCompiler(
                               /* scalaInstance     = */ scalaInstance,
                               /* compilerBridgeJar = */ jar,
                               /* classpathOptions  = */ classpathOptions)
      case _ =>
        ZincUtil.scalaCompiler(
          /* scalaInstance        = */ scalaInstance,
          /* classpathOptions     = */ classpathOptions,
          /* globalLock           = */ GlobalLock,
          /* componentProvider    = */ componentProvider,
          /* secondaryCacheDir    = */ Some(Paths.get(s"$home/.bloop/secondary-cache").toFile),
          /* dependencyResolution = */ DependencyResolution.getEngine,
          /* compilerBridgeSource = */ ZincUtil.getDefaultBridgeModule(scalaInstance.version),
          /* scalaJarsTarget      = */ scalaJarsTarget.toFile,
          /* log                  = */ logger
        )
    }
  }

  private final val ZINC_VERSION = "1.0.2"
  def bridgeComponentID(scalaVersion: String): String = {
    val shortScalaVersion = scalaVersion.take(4)
    val classVersion      = sys.props("java.class.version")
    s"org.scala-sbt-compiler-bridge_${shortScalaVersion}-${ZINC_VERSION}-bin_${scalaVersion}__${classVersion}"
  }
}

object CompilerCache {
  case class CacheId(scalaOrganization: String, scalaName: String, scalaVersion: String)
  object CacheId {
    def fromInstance(scalaInstance: ScalaInstance): CacheId =
      CacheId(scalaInstance.organization, scalaInstance.name, scalaInstance.version)
  }
}
