package bloop

import bloop.data.Project
import bloop.reporter.Reporter

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

package object reporter {
  type ConcurrentSet[A] = ConcurrentHashMap.KeySetView[A, java.lang.Boolean]
  object ConcurrentSet {
    def apply[A](): ConcurrentSet[A] = ConcurrentHashMap.newKeySet[A]()
  }

  implicit class ConcurrentSetLike[A](val underlying: ConcurrentSet[A]) extends AnyVal {
    def ++(elements: Seq[A]): Unit = { underlying.addAll(elements.asJava); () }
  }

  def createBuffer[A](project: Project): Reporter.Buffer[A] = {
    val needsConcurrentBuffer = project.scalaInstance.map(_.supportsHydra).getOrElse(false)
    Reporter.Buffer(needsConcurrentBuffer)
  }
}
