package bloop

import xsbti.compile.{IR, IRStore}

import scala.collection.mutable

final class SimpleIRStore private (irs: mutable.LinkedHashSet[Array[IR]]) extends IRStore {
  lazy val irsArray = irs.toArray
  override def getDependentsIRs: Array[Array[IR]] = irsArray
  override def merge(other: IRStore): IRStore =
    SimpleIRStore(other.getDependentsIRs ++ irsArray)
}

object SimpleIRStore {
  def apply(irs: Array[Array[IR]]): SimpleIRStore = {
    val lhs = new mutable.LinkedHashSet[Array[IR]]()
    irs.foreach(ir => lhs.+=(ir))
    new SimpleIRStore(lhs)
  }
}
