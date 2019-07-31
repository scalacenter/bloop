package buildpress

import java.net.URI

final case class Repository(id: String, uri: URI) {
  def isLocal: Boolean = {
    val scheme = uri.getScheme()
    scheme != null && scheme == "file"
  }

  def supportsGit: Boolean = {
    val scheme = uri.getScheme()
    scheme == "git" ||
    ((scheme == "http" || scheme == "https") && {
      val part = uri.getRawSchemeSpecificPart()
      part != null && part.endsWith(".git")
    })
  }

  def sha: Option[String] = Option(uri.getFragment())
  def uriWithoutSha: String =
    (new URI(uri.getScheme, uri.getSchemeSpecificPart, null)).toASCIIString()
  def uriWithNewSha(sha: String): URI =
    new URI(uri.getScheme, uri.getSchemeSpecificPart, sha)
}
