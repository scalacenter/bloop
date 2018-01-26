package build

import java.io.File

import sbt.Def
import sbt.io.syntax.fileToRichFile

import org.eclipse.jgit.api.{CloneCommand, Git}
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.{
  JschConfigSessionFactory,
  OpenSshConfig,
  SshSessionFactory,
  SshTransport
}
import com.jcraft.jsch.{UserInfo, Session}

/** Utility functions that help manipulate git repos */
object GitUtils {

  /** Open the git repository at `dir` and performs some operations. */
  def withGit[T](dir: File)(op: Git => T): T = {
    val git = Git.open(dir)
    try op(git)
    finally git.close()
  }

  /** The latest commit SHA in this repository. */
  def latestCommitIn(git: Git): String = {
    val repository = git.getRepository()
    val head = repository.resolve("HEAD")
    ObjectId.toString(head)
  }

  /**
   * Commit all the specified changes
   *
   * @param git     The git repository to work with.
   * @param changes The paths of the files that must be committed, relative to the repo's root.
   * @param message The commit message.
   */
  def commitChangesIn(git: Git, changes: Seq[String], message: String): Unit = {
    val add = git.add()
    val cmd = changes.foldLeft(git.add) {
      case (cmd, path) => cmd.addFilepattern(path)
    }
    cmd.call()
    git.commit.setMessage(message).call()
  }

  /** The latest tag in this repository. */
  def latestTagIn(git: Git): Option[String] = Option(git.describe().call())

  /**
   * The git directory for this submodule.
   *
   * This is different from the submodule's root: those don't have a `.git` directory
   * directly. It is in the `.git` directory of the root repository.
   */
  def submoduleGitDir(name: String) = Def.setting {
    BuildKeys.buildBase.value / ".git" / "modules" / name
  }

  /** Clone the repository at `uri` to `destination` and perform some operations. */
  def clone[T](uri: String, destination: File, sshSessionFactory: Option[SshSessionFactory] = None)(
      op: Git => T) = {
    val cmd =
      Git.cloneRepository().setDirectory(destination).setURI(uri).setTransportConfigCallback {
        case transport: SshTransport => sshSessionFactory.foreach(transport.setSshSessionFactory)
        case _ => ()
      }
    val git = cmd.call()
    try op(git)
    finally git.close()
  }

  /** Create a new tag in this repository. */
  def tag(git: Git, name: String, message: String): Unit = {
    git.tag().setName(name).setMessage(message).call()
  }

  /** Push the references in `refs` to `remote`. */
  def push(git: Git,
           remote: String,
           refs: Seq[String],
           sshSessionFactory: Option[SshSessionFactory] = None): Unit = {
    val cmdBase = git.push().setRemote(remote).setTransportConfigCallback {
      case transport: SshTransport => sshSessionFactory.foreach(transport.setSshSessionFactory)
      case _ => ()
    }
    val cmd = refs.foldLeft(cmdBase)(_ add _)
    cmd.call()
  }

  def defaultSshSessionFactory: SshSessionFactory = new JschConfigSessionFactory {
    override protected def configure(host: OpenSshConfig.Host, session: Session): Unit = {
      val userInfo = new UserInfo {
        override def getPassphrase(): String = null
        override def getPassword(): String = null
        override def promptPassword(message: String): Boolean = false
        override def promptPassphrase(message: String): Boolean = false
        override def promptYesNo(message: String): Boolean = false
        override def showMessage(message: String): Unit = ()
      }
      session.setUserInfo(userInfo)
      session.setConfig("StrictHostKeyChecking", "no")
    }
  }

}
