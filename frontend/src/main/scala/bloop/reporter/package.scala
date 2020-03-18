package bloop

import bloop.data.Project
import bloop.reporter.Reporter

import java.util.concurrent.ConcurrentHashMap

package object reporter {
  type ConcurrentSet[A] = ConcurrentHashMap.KeySetView[A, java.lang.Boolean]

  def createBuffer[A](project: Project): Reporter.Buffer[A] = {
    val needsConcurrentBuffer = project.scalaInstance.map(_.supportsHydra).getOrElse(false)
    Reporter.Buffer(needsConcurrentBuffer)
  }
}
