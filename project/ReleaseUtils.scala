package build

import java.io.File

import sbt.{Def, Keys, MessageOnlyException}
import sbt.io.syntax.fileToRichFile
import sbt.io.IO
import sbt.util.FileFunction

import GitUtils.GitAuth
import java.net.URL

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

  /**
   * Materializes a file-based Coursier channel to install a specific bloop version.
   */
  val bloopCoursierJson = Def.task {
    val bloopVersion = Keys.version.value
    val target = Keys.target.value
    val log = Keys.streams.value.log

    val cacheDirectory = Keys.streams.value.cacheDirectory
    val jsonPath = bloopCoursierJsonPath.value
    val jsonTarget = target / jsonPath.getName
    val bloopCliPath = "file://" + bloopPrebuiltCliPath.value.toString

    val lines = IO.readLines(jsonPath)
    val newContent = lines.map(
      _.replace("$VERSION", bloopVersion)
        .replace("$PREBUILT", bloopCliPath)
    )

    IO.writeLines(jsonTarget, newContent)
    jsonTarget
  }

  def installationArtifacts(
      bloopCoursierJson: File,
      buildBase: File,
      remoteTag: Option[String]
  ): Artifacts = {
    def artifact(f: File, label: String) = remoteTag match {
      case Some(tag) => Artifact.remote(f.getName(), tag, sha256(f), label)
      case None => Artifact.local(f.getName, f, sha256(f))
    }

    val bash = buildBase / "etc" / "bash" / "bloop"
    val zsh = buildBase / "etc" / "zsh" / "_bloop"
    val fish = buildBase / "etc" / "fish" / "bloop.fish"
    Artifacts(
      artifact(bloopCoursierJson, "coursier-channel"),
      artifact(bash, "bash-completions"),
      artifact(zsh, "zsh-completions"),
      artifact(fish, "fish-completions")
    )
  }

  val generateInstallationWitness = Def.task {
    val target = Keys.target.value
    val bloopVersion = Keys.version.value
    val witnessFile = target / "installed.txt"
    IO.writeLines(witnessFile, List(bloopVersion))
    witnessFile
  }

  case class Artifact(name: String, url: String, sha: String)

  object Artifact {
    def local(name: String, source: File, sha: String): Artifact = {
      // Scoop (Win) doesn't accept file URIs, only regular file paths
      val url =
        if (!scala.util.Properties.isWin) s"file://${source.getAbsolutePath}"
        else source.toPath.toUri.toString.replace("\\", "\\\\")
      Artifact(name, url, sha)
    }

    def remote(name: String, tagName: String, sha: String, label: String): Artifact = {
      val url = {
        if (label == "coursier-channel")
          s"https://github.com/scalacenter/bloop/releases/download/$tagName/$name.json"
        else if (label == "bash-completions")
          s"https://raw.githubusercontent.com/scalacenter/bloop/$tagName/etc/bash/bloop"
        else if (label == "zsh-completions")
          s"https://raw.githubusercontent.com/scalacenter/bloop/$tagName/etc/zsh/_bloop"
        else if (label == "fish-completions")
          s"https://raw.githubusercontent.com/scalacenter/bloop/$tagName/etc/fish/bloop.fish"
        else sys.error("Unrecognized label for artifact, can't create remote artifact!")
      }

      Artifact(name, url, sha)
    }
  }

  case class Artifacts(
      bloopCoursier: Artifact,
      bashAutocompletions: Artifact,
      zshAutocompletions: Artifact,
      fishAutocompletions: Artifact
  )

  val cwd = sys.props("user.dir")
  val ivyHome = new File(sys.props("user.home")) / ".ivy2/"

  /**
   * The content of the Homebrew Formula to install the version of Bloop that we're releasing.
   *
   * @param version The version of Bloop that we're releasing.
   * @param artifacts The local or remote artifacts we should use to build the formula.
   */
  def generateHomebrewFormulaContents(
      version: String,
      artifacts: Artifacts
  ): String = {
    s"""class Bloop < Formula
       |  desc "Installs the Bloop CLI for Bloop, a build server to compile, test and run Scala fast"
       |  homepage "https://github.com/scalacenter/bloop"
       |  version "$version"
       |  url "${artifacts.bloopCoursier.url}"
       |  sha256 "${artifacts.bloopCoursier.sha}"
       |  bottle :unneeded
       |
       |  depends_on "bash-completion"
       |  depends_on "coursier/formulas/coursier"
       |  depends_on :java => "1.8+"
       |
       |  resource "bash_completions" do
       |    url "${artifacts.bashAutocompletions.url}"
       |    sha256 "${artifacts.bashAutocompletions.sha}"
       |  end
       |
       |  resource "zsh_completions" do
       |    url "${artifacts.zshAutocompletions.url}"
       |    sha256 "${artifacts.zshAutocompletions.sha}"
       |  end
       |
       |  resource "fish_completions" do
       |    url "${artifacts.fishAutocompletions.url}"
       |    sha256 "${artifacts.fishAutocompletions.sha}"
       |  end
       |
       |  def install
       |      mkdir "bin"
       |      mkdir "channel"
       |
       |      mv "${artifacts.bloopCoursier.name}", "channel/bloop.json"
       |      system "coursier", "install", "--install-dir", "bin", "--default-channels=false", "--channel", "channel", "bloop", "-J-Divy.home=$ivyHome"
       |
       |      resource("bash_completions").stage { bash_completion.install "bloop" }
       |      resource("zsh_completions").stage { zsh_completion.install "_bloop" }
       |      resource("fish_completions").stage { fish_completion.install "bloop.fish" }
       |
       |      prefix.install "bin"
       |  end
       |
       |  test do
       |  end
       |end""".stripMargin
  }

  val createLocalHomebrewFormula = Def.task {
    val logger = Keys.streams.value.log
    val version = Keys.version.value
    val versionDir = Keys.target.value / version
    val targetLocalFormula = versionDir / "Bloop.rb"

    val coursierChannel = bloopCoursierJson.value
    val artifacts = installationArtifacts(coursierChannel, BuildKeys.buildBase.value, None)
    val contents = generateHomebrewFormulaContents(version, artifacts)

    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetLocalFormula, contents)
    logger.info(s"Local Homebrew formula created in ${targetLocalFormula.getAbsolutePath}")
  }

  /** Generate the new Homebrew formula, a new tag and push all that in our Homebrew tap */
  val updateHomebrewFormula = Def.task {
    val repository = "https://github.com/scalacenter/homebrew-bloop.git"
    val buildBase = BuildKeys.buildBase.value

    val coursierChannel = bloopCoursierJson.value

    val version = Keys.version.value
    val token = GitUtils.authToken()
    cloneAndPush(repository, buildBase, version, token, true) { inputs =>
      val formulaFileName = "bloop.rb"
      val artifacts = installationArtifacts(coursierChannel, buildBase, Some(inputs.tag))
      val contents = generateHomebrewFormulaContents(version, artifacts)
      FormulaArtifact(inputs.base / formulaFileName, contents) :: Nil
    }
  }

  def generateScoopFormulaContents(version: String, artifacts: Artifacts): String = {
    s"""{
       |  "version": "$version",
       |  "url": "${artifacts.bloopCoursier.url}",
       |  "hash": "sha256:${artifacts.bloopCoursier.sha}",
       |  "depends": "coursier",
       |  "bin": "bloop",
       |  "env_add_path": "$$dir",
       |  "env_set": {
       |    "BLOOP_HOME": "$$dir",
       |    "BLOOP_IN_SCOOP": "true"
       |  },
       |  "installer": {
       |    "script": "coursier install --install-dir $$dir --default-channels=false --channel $$dir bloop"
       |  }
       |}
        """.stripMargin
  }

  val createLocalScoopFormula = Def.task {
    val logger = Keys.streams.value.log
    val version = Keys.version.value
    val versionDir = Keys.target.value / version
    val coursierChannel = bloopCoursierJson.value
    val artifacts = installationArtifacts(coursierChannel, BuildKeys.buildBase.value, None)

    val formulaFileName = "bloop.json"
    val targetLocalFormula = versionDir / formulaFileName
    val contents = generateScoopFormulaContents(version, artifacts)

    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetLocalFormula, contents)
    logger.info(s"Local Scoop formula created in ${targetLocalFormula.getAbsolutePath}")
  }

  val updateScoopFormula = Def.task {
    val repository = "https://github.com/scalacenter/scoop-bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val version = Keys.version.value
    val coursierChannel = bloopCoursierJson.value
    val token = GitUtils.authToken()

    cloneAndPush(repository, buildBase, version, token, true) { inputs =>
      val formulaFileName = "bloop.json"
      val artifacts = installationArtifacts(coursierChannel, buildBase, Some(inputs.tag))
      val url = s"https://github.com/scalacenter/bloop/releases/download/${inputs.tag}/install.py"
      val contents = generateScoopFormulaContents(version, artifacts)
      FormulaArtifact(inputs.base / formulaFileName, contents) :: Nil
    }
  }

  def generateArchBuildContents(
      version: String,
      artifacts: Artifacts
  ): String = {
    // Note: pkgver must only contain letters, numbers and periods to be valid
    val safeVersion = version.replace('-', '.').replace('+', '.').replace(' ', '.')

    // Use unique names to avoid conflicts with cache and old package versions
    val coursierChannelName = s"bloop-coursier-channel-$safeVersion"
    val bashResourceName = s"bloop-bash-$safeVersion"
    val zshResourceName = s"bloop-zsh-$safeVersion"
    val fishResourceName = s"bloop-fish-$safeVersion"
    val coursierChannelRef = s"$coursierChannelName::${artifacts.bloopCoursier.url}"
    val bashResourceRef = s"$bashResourceName::${artifacts.bashAutocompletions.url}"
    val zshResourceRef = s"$zshResourceName::${artifacts.zshAutocompletions.url}"
    val fishResourceRef = s"$fishResourceName::${artifacts.fishAutocompletions.url}"

    s"""# Maintainer: Guillaume Raffin <theelectronwill@gmail.com>
       |# Generator: Bloop release utilities <https://github.com/scalacenter/bloop>
       |pkgname=bloop
       |pkgver=$safeVersion
       |pkgrel=1
       |pkgdesc="Bloop gives you fast edit/compile/test workflows for Scala."
       |arch=(any)
       |url="https://scalacenter.github.io/bloop/"
       |license=('Apache')
       |depends=('java-environment>=8' 'coursier>=2.0.0_RC6_7')
       |source=('$coursierChannelRef' '$bashResourceRef' '$zshResourceRef' '$fishResourceRef')
       |sha256sums=('${artifacts.bloopCoursier.sha}' '${artifacts.bashAutocompletions.sha}' '${artifacts.zshAutocompletions.sha}' '${artifacts.fishAutocompletions.sha}')
       |
       |build() {
       |  mkdir channel
       |  mv "$coursierChannelName" "channel/bloop.json"
       |  coursier install --install-dir "$$srcdir" --default-channels=false --channel channel bloop
       |}
       |
       |package() {
       |  cd "$$srcdir"
       |
       |  ## binaries
       |  # we use /usr/lib/bloop so that we can add a .jvmopts file in it
       |  install -Dm755 bloop "$$pkgdir"/usr/lib/bloop/bloop
       |
       |  # links in /usr/bin
       |  mkdir -p "$$pkgdir/usr/bin"
       |  ln -s /usr/lib/bloop/bloop "$$pkgdir"/usr/bin/bloop
       |
       |  # shell completion
       |  install -Dm644 $bashResourceName "$$pkgdir"/etc/bash_completion.d/bloop
       |  install -Dm644 $zshResourceName "$$pkgdir"/usr/share/zsh/site-functions/_bloop
       |  install -Dm644 $fishResourceName "$$pkgdir"/usr/share/fish/vendor_completions.d/bloop.fish
       |}
       |""".stripMargin
  }

  def generateArchInfoContents(
      version: String,
      artifacts: Artifacts
  ): String = {
    s"""pkgbase = bloop
       |pkgdesc = Bloop gives you fast edit/compile/test workflows for Scala.
       |pkgver = ${version.replace('-', '.').replace('+', '.')}
       |pkgrel = 1
       |url = https://scalacenter.github.io/bloop/
       |arch = any
       |license = Apache
       |depends = java-environment>=8
       |depends = coursier>=2.0.0_RC6_7
       |source = ${artifacts.bloopCoursier.url}
       |sha256sums = ${artifacts.bloopCoursier.sha}
       |source = ${artifacts.bashAutocompletions.url}
       |sha256sums = ${artifacts.bashAutocompletions.sha}
       |source = ${artifacts.zshAutocompletions.url}
       |sha256sums = ${artifacts.zshAutocompletions.sha}
       |source = ${artifacts.fishAutocompletions.url}
       |sha256sums = ${artifacts.fishAutocompletions.sha}
       |pkgname = bloop
       |""".stripMargin
  }

  /**
   * Creates two files: PKGBUILD and .SRCINFO, which can be used to locally build a bloop package
   * for ArchLinux with the makepkg command.
   */
  val createLocalArchPackage = Def.task {
    val logger = Keys.streams.value.log
    val version = Keys.version.value
    val versionDir = Keys.target.value / version
    val targetBuild = versionDir / "PKGBUILD"
    val targetInfo = versionDir / ".SRCINFO"
    val coursierChannel = bloopCoursierJson.value
    val artifacts = installationArtifacts(coursierChannel, BuildKeys.buildBase.value, None)
    val pkgbuild = generateArchBuildContents(version, artifacts)
    val srcinfo = generateArchInfoContents(version, artifacts)
    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetBuild, pkgbuild)
    IO.write(targetInfo, srcinfo)
    logger.info(s"Local ArchLinux package build files created in ${versionDir.getAbsolutePath}")
  }

  val updateArchPackage = Def.task {
    val repository = "ssh://aur@aur.archlinux.org/bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val version = Keys.version.value
    val sshKey = GitUtils.authSshKey()
    val coursierChannel = bloopCoursierJson.value

    cloneAndPush(repository, buildBase, version, sshKey, false) { inputs =>
      val buildFile = inputs.base / "PKGBUILD"
      val infoFile = inputs.base / ".SRCINFO"
      val artifacts = installationArtifacts(coursierChannel, buildBase, Some(inputs.tag))
      val buildContents = generateArchBuildContents(version, artifacts)
      val infoContents = generateArchInfoContents(version, artifacts)
      FormulaArtifact(buildFile, buildContents) :: FormulaArtifact(infoFile, infoContents) :: Nil
    }
  }

  case class FormulaInputs(tag: String, base: File)
  case class FormulaArtifact(target: File, contents: String)

  private final val bloopoidName = "Bloopoid"
  private final val bloopoidEmail = "bloop@trashmail.ws"

  /** Clones a git repository, generates a formula/package and pushes the result.*/
  def cloneAndPush(
      repository: String,
      buildBase: File,
      version: String,
      auth: GitAuth,
      pushTag: Boolean
  )(
      generateFormula: FormulaInputs => Seq[FormulaArtifact]
  ): Unit = {
    val tagName = GitUtils.withGit(buildBase)(GitUtils.latestTagIn(_)).getOrElse {
      throw new MessageOnlyException("No tag found in this repository.")
    }
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
          GitUtils.push(gitRepo, "origin", Seq("master", tagName), auth)
        } else {
          // The AUR hooks block git tags: don't try to use them (set pushTag=false)
          GitUtils.push(gitRepo, "origin", Seq("master"), auth)
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
