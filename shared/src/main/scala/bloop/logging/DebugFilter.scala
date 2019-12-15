package bloop.logging

sealed trait DebugFilter { self =>
  private[logging] def isEnabledFor(other: DebugFilter): Boolean =
    DebugFilter.checkSubsumption(self, other)
}

object DebugFilter {
  final case object All extends DebugFilter
  final case object Link extends DebugFilter
  final case object Run extends DebugFilter
  final case object Bsp extends DebugFilter
  final case object Test extends DebugFilter
  final case object Compilation extends DebugFilter
  final case object FileWatching extends DebugFilter
  final case class Aggregate(filters: List[DebugFilter]) extends DebugFilter

  def toUniqueFilter(filters: List[DebugFilter]): DebugFilter = {
    filters match {
      case Nil => DebugFilter.All
      case x :: Nil => DebugFilter.All
      case xs =>
        if (xs.contains(DebugFilter.All)) DebugFilter.All
        else DebugFilter.Aggregate(xs)
    }
  }

  /**
   * Check if `filter1` subsumes `filter2`. Note that subsumption of debug filters is
   * commutative so that if the debug filter of a logger is, say, `Compile`, the use
   * sites can debug normal statements tagged as `DebugFilter.All`.
   *
   * @param filter1 The first filter to check for subsumption.
   * @param filter2 The second filter to check for subsumption.
   * @return Whether filter2 is subsumed in filter1 or viceversa.
   */
  private[bloop] def checkSubsumption(filter1: DebugFilter, filter2: DebugFilter): Boolean = {
    def isFilterSubsumed(owner: DebugFilter, check: DebugFilter): Boolean = {
      (owner, check) match {
        case (DebugFilter.All, _) => true
        case (_, DebugFilter.All) => true
        case (ctx1, ctx2) if ctx1 == ctx2 => true
        case _ => false
      }
    }

    (filter1, filter2) match {
      case (DebugFilter.Aggregate(filters), DebugFilter.Aggregate(filters2)) =>
        filters.exists(filter => filters2.exists(filter2 => isFilterSubsumed(filter, filter2)))
      case (DebugFilter.Aggregate(filters), target) =>
        filters.exists(filter => isFilterSubsumed(filter, target))
      case (target, DebugFilter.Aggregate(filters)) =>
        filters.exists(filter => isFilterSubsumed(target, filter))
      case (one, another) => isFilterSubsumed(one, another)
    }
  }
}
