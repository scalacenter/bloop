package buildpress
import java.net.URI
import bloop.launcher.core.Shell

object Fetcher {
  def fetchGitURI(origin: URI, shell: Shell): Either[BuildpressError.CloningFailure, Unit] = ???
}
