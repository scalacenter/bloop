package blossom

import sbt.librarymanagement._
import sbt.librarymanagement.ivy._

object DependencyResolution {
  def getEngine(inputs: Project): DependencyResolution = {
    val configuration = InlineIvyConfiguration()
    IvyDependencyResolution(configuration)
  }
}
