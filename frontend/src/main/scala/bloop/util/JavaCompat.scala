package bloop.util

import java.util.Optional
import java.util.function.Supplier

import xsbti.T2
import sbt.util.InterfaceUtil

object JavaCompat {
  implicit def toSupplier[T](thunk: => T): Supplier[T] = thunk

  implicit class EnrichSbtTuple[T, U](sbtTuple: T2[T, U]) {
    def toScalaTuple: (T, U) = sbtTuple.get1() -> sbtTuple.get2()
  }

  implicit class EnrichOptional[T](optional: Optional[T]) {
    def toOption: Option[T] = InterfaceUtil.toOption(optional)
  }

  implicit class EnrichOption[T](option: Option[T]) {
    def toOptional: Optional[T] = InterfaceUtil.toOptional(option)
  }
}
