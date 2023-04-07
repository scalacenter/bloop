package build

import java.io.File

import sbt.{Def, Keys}
import sbt.io.syntax.fileToRichFile
import sbt.io.IO

import GitUtils.GitAuth

/** Utilities that are useful for releasing Bloop */
object ReleaseUtils {

  /** The path to our installation script */
  private val bloopCoursierJsonPath = Def.setting {
    BuildKeys.buildBase.value / "etc" / "bloop-coursier.json"
  }

  /** The path to the platform-specific GraalVM Native image binary */
  private val bloopPrebuiltCliPath = Def.setting {
    BuildKeys.buildBase.value / "bloopgun" / "target" / "graalvm-native-image" / "bloopgun-core"
  }

  val bloopCoursierJson = Def.task {
    val bloopVersion = Keys.version.value
    val target = Keys.target.value
    val log = Keys.streams.value.log

    val jsonPath = bloopCoursierJsonPath.value
    IO.createDirectory(target / "stable")
    val jsonTarget = target / "stable" / jsonPath.getName

    // Interpolated variables are replaced by coursier itself
    val bloopCliPath =
      "https://github.com/scalacenter/bloop/releases/download/v${version}/bloop-${platform}"

    val lines = IO.readLines(jsonPath)
    val newContent = lines.map(
      _.replace("$VERSION", bloopVersion)
        .replace("$PREBUILT", bloopCliPath)
    )

    IO.writeLines(jsonTarget, newContent)
    jsonTarget
  }

  /**
   * Materializes a file-based Coursier channel to install a specific bloop version.
   */
  val bloopLocalCoursierJson = Def.task {
    val bloopVersion = Keys.version.value
    val target = Keys.target.value
    val log = Keys.streams.value.log

    val jsonPath = bloopCoursierJsonPath.value
    IO.createDirectory(target / "local")
    val jsonTarget = target / "local" / jsonPath.getName
    val bloopCliPath = "file://" + bloopPrebuiltCliPath.value.toString

    val lines = IO.readLines(jsonPath)
    val newContent = lines.map(
      _.replace("$VERSION", bloopVersion)
        .replace("$PREBUILT", bloopCliPath)
    )

    IO.writeLines(jsonTarget, newContent)
    jsonTarget
  }

  case class Artifact(name: String, url: String, sha: String)

  case class Artifacts(
      bloopCoursier: Artifact,
      bashAutocompletions: Artifact,
      zshAutocompletions: Artifact,
      fishAutocompletions: Artifact
  )

  case class FormulaInputs(tag: String, base: File)
  case class FormulaArtifact(target: File, contents: String)

  private final val bloopoidName = "Bloopoid"
  private final val bloopoidEmail = "bloop@trashmail.ws"

  /** Clones a git repository, generates a formula/package and pushes the result. */
  def cloneAndPush(
      repository: String,
      version: String,
      auth: GitAuth,
      pushTag: Boolean
  )(
      generateFormula: FormulaInputs => Seq[FormulaArtifact]
  ): Unit = {
    val tagName = s"v$version"
    IO.withTemporaryDirectory { tmpDir =>
      GitUtils.clone(repository, tmpDir, auth) { gitRepo =>
        val commitMessage = s"Updating to Bloop $tagName"
        val artifacts = generateFormula(FormulaInputs(tagName, tmpDir))
        artifacts.foreach(a => IO.write(a.target, a.contents))
        val changes = artifacts.map(a => a.target.getName)
        GitUtils.commitChangesIn(
          gitRepo,
          changes,
          commitMessage,
          bloopoidName,
          bloopoidEmail
        )
        if (pushTag) {
          GitUtils.tag(gitRepo, tagName, commitMessage)
          GitUtils.push(gitRepo, "origin", Seq("main", tagName), auth)
        } else {
          // The AUR hooks block git tags: don't try to use them (set pushTag=false)
          GitUtils.push(gitRepo, "origin", Seq("main"), auth)
        }
      }
    }
  }

  def sha256(file: sbt.File): String = {
    import _root_.org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256
    import _root_.org.apache.commons.codec.digest.DigestUtils
    new DigestUtils(SHA_256).digestAsHex(file)
  }
}
