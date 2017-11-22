package bloop

import java.util.concurrent.ConcurrentHashMap

import bloop.io.{AbsolutePath, Paths}
import sbt.internal.inc.bloop.ZincInternals
import sbt.internal.inc.{AnalyzingCompiler, ZincUtil}
import sbt.librarymanagement.Resolver
import xsbti.ComponentProvider
import xsbti.compile.{ClasspathOptions, Compilers}

class CompilerCache(componentProvider: ComponentProvider,
                    retrieveDir: AbsolutePath,
                    userResolvers: List[Resolver] = Nil) {
  import CompilerCache.CacheId
  private val logger = QuietLogger
  private val cache = new ConcurrentHashMap[CacheId, Compilers]()

  def get(id: CacheId): Compilers = cache.computeIfAbsent(id, newCompilers)

  private def newCompilers(cacheId: CacheId): Compilers = {
    val scalaInstance =
      ScalaInstance(cacheId.scalaOrganization, cacheId.scalaName, cacheId.scalaVersion)
    val classpathOptions = ClasspathOptions.of(true, false, false, true, false)
    val compiler = getScalaCompiler(scalaInstance, classpathOptions, componentProvider)
    ZincUtil.compilers(scalaInstance, classpathOptions, None, compiler)
  }

  def getScalaCompiler(scalaInstance: ScalaInstance,
                       classpathOptions: ClasspathOptions,
                       componentProvider: ComponentProvider): AnalyzingCompiler = {
    val bridgeSources = ZincInternals.getModuleForBridgeSources(scalaInstance)
    val bridgeId = ZincInternals.getBridgeComponentId(bridgeSources, scalaInstance)
    componentProvider.component(bridgeId) match {
      case Array(jar) => ZincUtil.scalaCompiler(scalaInstance, jar, classpathOptions)
      case _ =>
        ZincUtil.scalaCompiler(
          /* scalaInstance        = */ scalaInstance,
          /* classpathOptions     = */ classpathOptions,
          /* globalLock           = */ Lock,
          /* componentProvider    = */ componentProvider,
          /* secondaryCacheDir    = */ Some(Paths.getCacheDirectory("bridge-cache").toFile),
          /* dependencyResolution = */ DependencyResolution.getEngine(userResolvers),
          /* compilerBridgeSource = */ bridgeSources,
          /* scalaJarsTarget      = */ retrieveDir.toFile,
          /* log                  = */ logger
        )
    }
  }
}

object CompilerCache {
  case class CacheId(scalaOrganization: String, scalaName: String, scalaVersion: String)
  object CacheId {
    def fromInstance(scalaInstance: ScalaInstance): CacheId =
      CacheId(scalaInstance.organization, scalaInstance.name, scalaInstance.version)
  }
}
