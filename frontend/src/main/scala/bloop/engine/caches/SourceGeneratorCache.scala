package bloop.engine.caches

import java.util.concurrent.ConcurrentHashMap

import bloop.cli.CommonOptions
import bloop.engine.SourceGenerator
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.task.Task

final class SourceGeneratorCache private (
    cache: ConcurrentHashMap[SourceGenerator, Task[SourceGenerator.Run]]
) {
  def update(
      sourceGenerator: SourceGenerator,
      logger: Logger,
      opts: CommonOptions
  ): Task[List[AbsolutePath]] = {
    cache
      .compute(
        sourceGenerator,
        { (_, prev) =>
          val previous = Option(prev).getOrElse(Task.now(SourceGenerator.NoRun))
          previous.flatMap(sourceGenerator.update(_, logger, opts)).memoize
        }
      )
      .map {
        case SourceGenerator.NoRun => Nil
        case SourceGenerator.PreviousRun(_, outputs) => outputs.keys.toList.sortBy(_.syntax)
      }
  }
}

object SourceGeneratorCache {
  def empty: SourceGeneratorCache = new SourceGeneratorCache(new ConcurrentHashMap())
}
