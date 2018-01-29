package build

import java.io.File

import sbt.Def
import sbt.io.syntax.fileToRichFile

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

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
  def commitChangesIn(git: Git,
                      changes: Seq[String],
                      message: String,
                      committerName: String,
                      committerEmail: String): Unit = {
    val add = git.add()
    val cmd = changes.foldLeft(git.add) {
      case (cmd, path) => cmd.addFilepattern(path)
    }
    cmd.call()
    git.commit.setMessage(message).setCommitter(committerName, committerEmail).call()
  }

  /** The latest tag in this repository. */
  def latestTagIn(git: Git): Option[String] = Option(git.describe().call())

  /** Clone the repository at `uri` to `destination` and perform some operations. */
  def clone[T](uri: String, destination: File, token: String)(op: Git => T) = {
    val cmd =
      Git
        .cloneRepository()
        .setDirectory(destination)
        .setURI(uri)
        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
    val git = cmd.call()
    try op(git)
    finally git.close()
  }

  /** Create a new tag in this repository. */
  def tag(git: Git, name: String, message: String): Unit = {
    git.tag().setName(name).setMessage(message).call()
  }

  /** Push the references in `refs` to `remote`. */
  def push(git: Git, remote: String, refs: Seq[String], token: String): Unit = {
    val cmdBase = git
      .push()
      .setRemote(remote)
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
    val cmd = refs.foldLeft(cmdBase)(_ add _)
    cmd.call()
  }

}
