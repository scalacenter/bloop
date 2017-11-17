package bloop

import sbt.librarymanagement._
import sbt.librarymanagement.ivy._

object DependencyResolution {
  private final val BloopResolvers = Vector(Resolver.defaultLocal, Resolver.mavenCentral)
  def getEngine: DependencyResolution = {
    val configuration = InlineIvyConfiguration().withResolvers(BloopResolvers)
    IvyDependencyResolution(configuration)
  }
}
