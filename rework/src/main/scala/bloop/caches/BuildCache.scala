package bloop.caches

import bloop.Build
import bloop.io.AbsolutePath

import java.util.concurrent.ConcurrentHashMap
final class BuildCache(cache: ConcurrentHashMap[AbsolutePath, Build]) {
  import Compat.JavaFunction
  def getBuildFor(path: AbsolutePath): Option[Build] = Option(cache.get(path))
  def updateBuild(build: Build): Unit = cache.put(build.loadedFrom, build)
  def addIfMissing(from: AbsolutePath, computeBuild: AbsolutePath => Build): Unit =
    cache.computeIfAbsent(from, computeBuild.toJava)
}

object BuildCache {
  def empty: BuildCache = new BuildCache(new ConcurrentHashMap())
}
