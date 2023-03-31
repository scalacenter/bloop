package bloop.rifle
case class BloopVersion(raw: String) {
  def isOlderThan(other: BloopVersion) = {
    val bloopVRegex = "([0-9]+).([0-9]+)[.]([0-9]+)[-]?([0-9]+)?".r
    def unwrapVersionString(v: String) =
      List(1, 2, 3, 4).map(bloopVRegex.findAllIn(v).group(_)).map(x =>
        if (x != null) x.toInt else 0
      )
    val rhsMatch = unwrapVersionString(other.raw)
    val lhsMatch = unwrapVersionString(raw)
    lhsMatch.zip(rhsMatch).find { case (l, r) => l != r }.exists { case (l, r) => l < r }
  }
}
