package build

import java.io.File

import sbt.{Def, MessageOnlyException}
import sbt.io.syntax.fileToRichFile

import _root_.org.eclipse.jgit.api.{Git, TransportCommand, PushCommand, CloneCommand}
import _root_.org.eclipse.jgit.lib.ObjectId
import _root_.org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/** Utility functions that help manipulate git repos */
object GitUtils {

  /** Open the git repository at `dir` and performs some operations. */
  def withGit[T](dir: File)(op: Git => T): T = {
    val git = Git.open(dir)
    try op(git)
    finally git.close()
  }

  /**
   * Commit all the specified changes
   *
   * @param git     The git repository to work with.
   * @param changes The paths of the files that must be committed, relative to the repo's root.
   * @param message The commit message.
   */
  def commitChangesIn(
      git: Git,
      changes: Seq[String],
      message: String,
      committerName: String,
      committerEmail: String
  ): Unit = {
    val add = git.add()
    val cmd = changes.foldLeft(git.add) {
      case (cmd, path) => cmd.addFilepattern(path)
    }
    cmd.call()
    git.commit.setMessage(message).setCommitter(committerName, committerEmail).call()
  }

  /** The latest tag in this repository. */
  def latestTagIn(git: Git): Option[String] = Option(git.describe().call())

  /** Function that takes a TransportCommand and applies some authentication method on it. */
  type GitAuth = TransportCommand[_, _] => Unit

  /** Github access token given by the environment variable BLOOPOID_GITHUB_TOKEN. */
  private def getEnvToken: String = sys.env.get("BLOOPOID_GITHUB_TOKEN").getOrElse {
    throw new MessageOnlyException("Couldn't find Github oauth token in `BLOOPOID_GITHUB_TOKEN`")
  }

  /** SSH private key file given by the environment variable BLOOPOID_AUR_KEY_PATH. */
  private def getEnvSshKey: File =
    sys.env.get("BLOOPOID_PRIVATE_KEY_PATH").map(new File(_)).getOrElse {
      throw new MessageOnlyException("Couldn't find AUR ssh key in `BLOOPOID_PRIVATE_KEY_PATH`")
    }

  /**
   * Returns a function that uses the given access token to authenticate the user with jgit.
   *
   * @param token the access token
   */
  def authToken(token: String = getEnvToken): GitAuth = {
    _.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
  }

  /**
   * Returns a function that uses the given SSH key to authenticate the user with jgit.
   *
   * @param keyFile the file that contains the private SSH key
   */
  def authSshKey(keyFile: File = getEnvSshKey): GitAuth = {
    import _root_.com.jcraft.jsch.Session
    import _root_.org.eclipse.jgit.api.TransportConfigCallback
    import _root_.org.eclipse.jgit.transport.{JschConfigSessionFactory, Transport, SshTransport}
    import _root_.org.eclipse.jgit.transport.OpenSshConfig.Host
    import _root_.org.eclipse.jgit.util.FS

    val sessionFactory = new JschConfigSessionFactory {
      override def configure(h: Host, s: Session) = {
        s.setConfig("StrictHostKeyChecking", "no")
      }
      override protected def createDefaultJSch(fs: FS) = {
        val jsch = super.createDefaultJSch(fs)
        jsch.addIdentity(keyFile.getAbsolutePath)
        jsch
      }
    }
    val callback = new TransportConfigCallback {
      override def configure(transport: Transport) = {
        transport.asInstanceOf[SshTransport].setSshSessionFactory(sessionFactory)
      }
    }
    cmd => cmd.setTransportConfigCallback(callback)
  }

  /** Clone the repository at `uri` to `destination` and perform some operations. */
  def clone[T](uri: String, destination: File, auth: GitAuth)(op: Git => T) = {
    val cmd =
      Git
        .cloneRepository()
        .setDirectory(destination)
        .setURI(uri)
    auth(cmd)
    val git = cmd.call()
    try op(git)
    finally git.close()
  }

  /** Create a new tag in this repository. */
  def tag(git: Git, name: String, message: String): Unit = {
    git.tag().setName(name).setMessage(message).call()
  }

  /** Push the references in `refs` to `remote`. */
  def push(git: Git, remote: String, refs: Seq[String], auth: GitAuth): Unit = {
    val cmdBase = git
      .push()
      .setRemote(remote)
    auth(cmdBase)
    val cmd = refs.foldLeft(cmdBase)(_ add _)
    cmd.call()
  }
}
