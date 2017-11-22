package bloop

import sbt.librarymanagement._
import sbt.librarymanagement.ivy._

object DependencyResolution {
  private final val BloopResolvers = Vector(Resolver.defaultLocal, Resolver.mavenCentral)
  def getEngine(userResolvers: List[Resolver]): DependencyResolution = {
    val resolvers = if (userResolvers.isEmpty) BloopResolvers else userResolvers.toVector
    val configuration = InlineIvyConfiguration().withResolvers(resolvers)
    IvyDependencyResolution(configuration)
  }
}
