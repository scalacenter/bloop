package bloop

import java.util.concurrent.ConcurrentHashMap

import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger
import sbt.internal.inc.bloop.ZincInternals
import sbt.internal.inc.{AnalyzingCompiler, ZincUtil}
import sbt.librarymanagement.Resolver
import xsbti.ComponentProvider
import xsbti.compile.Compilers

class CompilerCache(componentProvider: ComponentProvider,
                    retrieveDir: AbsolutePath,
                    logger: Logger,
                    userResolvers: List[Resolver]) {

  private val cache = new ConcurrentHashMap[ScalaInstance, Compilers]()

  def get(scalaInstance: ScalaInstance): Compilers =
    cache.computeIfAbsent(scalaInstance, newCompilers)

  private def newCompilers(scalaInstance: ScalaInstance): Compilers = {
    val compiler = getScalaCompiler(scalaInstance, componentProvider)
    ZincUtil.compilers(scalaInstance, None, compiler)
  }

  def getScalaCompiler(scalaInstance: ScalaInstance,
                       componentProvider: ComponentProvider): AnalyzingCompiler = {
    val bridgeSources = ZincInternals.getModuleForBridgeSources(scalaInstance)
    val bridgeId = ZincInternals.getBridgeComponentId(bridgeSources, scalaInstance)
    componentProvider.component(bridgeId) match {
      case Array(jar) => ZincUtil.scalaCompiler(scalaInstance, jar)
      case _ =>
        ZincUtil.scalaCompiler(
          /* scalaInstance        = */ scalaInstance,
          /* globalLock           = */ BloopComponentsLock,
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
