package bloop.engine.caches

import java.util.concurrent.ConcurrentHashMap

import bloop.cli.CommonOptions
import bloop.engine.SourceGenerator
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.task.Task

final class SourceGeneratorCache private (
    cache: ConcurrentHashMap[SourceGenerator, SourceGenerator.Run]
) {
  def update(
      sourceGenerator: SourceGenerator,
      logger: Logger,
      opts: CommonOptions
  ): Task[List[AbsolutePath]] = {
    val previous = getStateFor(sourceGenerator)
    sourceGenerator.update(previous, logger, opts).map {
      case SourceGenerator.NoRun => Nil
      case SourceGenerator.PreviousRun(_, outputs) => outputs.keys.toList.sortBy(_.syntax)
    }
  }

  private def getStateFor(sourceGenerator: SourceGenerator): SourceGenerator.Run = {
    Option(cache.get(sourceGenerator)).getOrElse(SourceGenerator.NoRun)
  }
}

object SourceGeneratorCache {
  def empty: SourceGeneratorCache = new SourceGeneratorCache(new ConcurrentHashMap())
}
