package buildpress
import buildpress.io.AbsolutePath

sealed trait BuildpressError extends Throwable
object BuildpressError {
  private[buildpress] trait OverrideCause {
    this: Throwable =>
    def cause: Option[Throwable]
    override def getCause(): Throwable = cause.getOrElse(this.getCause())
  }

  final case class InvalidBuildpressHome(msg: String) extends BuildpressError

  final case class CloningFailure(msg: String, cause: Option[Throwable])
      extends BuildpressError
      with OverrideCause

  final case class PersistFailure(msg: String, cause: Option[Throwable])
      extends BuildpressError
      with OverrideCause

  final case class ImportFailure(msg: String, cause: Option[Throwable])
      extends BuildpressError
      with OverrideCause

  final case class ParseFailure(msg: String, cause: Option[Throwable])
      extends BuildpressError
      with OverrideCause
}
