package bloop

import xsbti.compile.{IR, IRStore}

final class SimpleIRStore(irs: Array[Array[IR]]) extends IRStore {
  override def getDependentsIRs: Array[Array[IR]] = irs
  override def merge(other: IRStore): IRStore =
    new SimpleIRStore(other.getDependentsIRs ++ irs)
}
