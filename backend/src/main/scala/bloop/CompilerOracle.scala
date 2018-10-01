package bloop

import java.io.File
import java.util.concurrent.CompletableFuture

import bloop.io.AbsolutePath

trait CompilerOracle {
  def getTransitiveJavaSourcesOfOngoingCompilations: List[File]
}

object CompilerOracle {
  def apply[T](
      signalsAndSources: List[(CompletableFuture[T], List[AbsolutePath])]
  ): CompilerOracle = {
    new CompilerOracle {
      override def getTransitiveJavaSourcesOfOngoingCompilations: List[File] = {
        signalsAndSources.flatMap {
          case (signal, sources) if signal.isDone => Nil
          case (_, sources) => sources.map(_.toFile)
        }
      }
    }
  }

  final val empty: CompilerOracle = CompilerOracle(Nil)
}
