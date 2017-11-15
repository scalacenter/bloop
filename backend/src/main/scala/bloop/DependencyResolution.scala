package bloop

import sbt.librarymanagement._
import sbt.librarymanagement.ivy._

object DependencyResolution {
  def getEngine: DependencyResolution = {
    val configuration =
      InlineIvyConfiguration().withResolvers(Resolver.defaults ++ Seq(Resolver.defaultLocal))
    IvyDependencyResolution(configuration)
  }
}
