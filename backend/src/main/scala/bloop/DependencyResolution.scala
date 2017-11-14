package bloop

import sbt.librarymanagement._
import sbt.librarymanagement.ivy._

object DependencyResolution {
  def getEngine: DependencyResolution = {
    val configuration = InlineIvyConfiguration()
    IvyDependencyResolution(configuration)
  }
}
