package bloop

import bloop.data.Project
import bloop.reporter.Reporter

package object reporter {
  def createBuffer[A](project: Project): Reporter.Buffer[A] = {
    val needsConcurrentBuffer = project.scalaInstance.map(_.supportsHydra).getOrElse(false)
    Reporter.Buffer(needsConcurrentBuffer)
  }
}
