package buildpress
import java.net.URI

sealed trait Repository
object Repository {
  // TODO: Support hg and svn
  final case object Unsupported extends Repository
  final case class Git(origin: URI, hash: String) extends Repository {
      def hash: String = origin.getFragment()
  }

  def toRepository(id: String, uri: URI): Either[]
}
