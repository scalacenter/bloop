package build

import java.io.File

import sbt.{Def, Keys, MessageOnlyException}
import sbt.io.syntax.fileToRichFile
import sbt.io.IO
import sbt.util.FileFunction

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

  val generateInstallationWitness = Def.task {
    val target = Keys.target.value
    val bloopVersion = Keys.version.value
    val witnessFile = target / "installed.txt"
    IO.writeLines(witnessFile, List(bloopVersion))
    witnessFile
  }

  /* Defines an origin where the left is a path to a local file and the right a tag name. */
  type FormulaOrigin = Either[File, String]

  /**
   * The content of the Homebrew Formula to install the version of Bloop that we're releasing.
   *
   * @param version The version of Bloop that we're releasing.
   * @param origin The origin where we install the homebrew formula from.
   * @param sha The SHA-256 of the bloop coursier channel.
   */
  def generateHomebrewFormulaContents(
      version: String,
      origin: FormulaOrigin,
      sha: String,
      local: Boolean
  ): String = {
    val url = {
      origin match {
        case Left(f) => s"""url "file://${f.getAbsolutePath}""""
        case Right(tagName) =>
          s"""url "https://github.com/scalacenter/bloop/releases/download/$tagName/bloop-coursier.json""""
      }
    }

    s"""class Bloop < Formula
       |  desc "Installs the Bloop CLI for Bloop, a build server to compile, test and run Scala fast"
       |  homepage "https://github.com/scalacenter/bloop"
       |  version "$version"
       |  $url
       |  sha256 "$sha"
       |  bottle :unneeded
       |
       |  depends_on "bash-completion"
       |  depends_on "coursier/formulas/coursier"
       |  depends_on :java => "1.8+"
       |
       |  def install
       |      mkdir "bin"
       |      mkdir "channel"
       |      mv "bloop-coursier.json", "channel/bloop.json"
       |      system "coursier", "install", "--install-dir", "bin", "--default-channels=false", "--channel", "channel", "bloop"
       |      zsh_completion.install "bin/zsh/_bloop"
       |      bash_completion.install "bin/bash/bloop"
       |      fish_completion.install "bin/fish/bloop.fish"
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
    val channelSha = sha256(coursierChannel)
    val contents = generateHomebrewFormulaContents(version, Left(coursierChannel), channelSha, true)
    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetLocalFormula, contents)
    logger.info(s"Local Homebrew formula created in ${targetLocalFormula.getAbsolutePath}")
  }

  /** Generate the new Homebrew formula, a new tag and push all that in our Homebrew tap */
  val updateHomebrewFormula = Def.task {
    val repository = "https://github.com/scalacenter/homebrew-bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val channelSha = sha256(bloopCoursierJson.value)
    val version = Keys.version.value
    val token = GitUtils.authToken()
    cloneAndPush(repository, buildBase, version, token, true) { inputs =>
      val formulaFileName = "bloop.rb"
      val contents = generateHomebrewFormulaContents(version, Right(inputs.tag), channelSha, false)
      FormulaArtifact(inputs.base / formulaFileName, contents) :: Nil
    }
  }

  def generateScoopFormulaContents(version: String, sha: String, origin: FormulaOrigin): String = {
    val url = {
      origin match {
        case Left(f) => s"""${f.toPath.toUri.toString.replace("\\", "\\\\")}"""
        case Right(tag) => s"https://github.com/scalacenter/bloop/releases/download/$tag/install.py"
      }
    }

    s"""{
       |  "version": "$version",
       |  "url": "$url",
       |  "hash": "sha256:$sha",
       |  "depends": "python",
       |  "bin": "bloop.cmd",
       |  "env_add_path": "$$dir",
       |  "env_set": {
       |    "BLOOP_HOME": "$$dir",
       |    "BLOOP_IN_SCOOP": "true"
       |  },
       |  "installer": {
       |    "script": "python $$dir/install.py --dest $$dir"
       |  }
       |}
        """.stripMargin
  }

  val createLocalScoopFormula = Def.task {
    val logger = Keys.streams.value.log
    val version = Keys.version.value
    val versionDir = Keys.target.value / version
    val coursierChannel = bloopCoursierJson.value
    val channelSha = sha256(coursierChannel)

    val formulaFileName = "bloop.json"
    val targetLocalFormula = versionDir / formulaFileName
    val contents = generateScoopFormulaContents(version, channelSha, Left(coursierChannel))

    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetLocalFormula, contents)
    logger.info(s"Local Scoop formula created in ${targetLocalFormula.getAbsolutePath}")
  }

  val updateScoopFormula = Def.task {
    val repository = "https://github.com/scalacenter/scoop-bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val version = Keys.version.value
    val coursierChannel = bloopCoursierJson.value
    val channelSha = sha256(coursierChannel)
    val token = GitUtils.authToken()
    cloneAndPush(repository, buildBase, version, token, true) { inputs =>
      val formulaFileName = "bloop.json"
      val url = s"https://github.com/scalacenter/bloop/releases/download/${inputs.tag}/install.py"
      val contents = generateScoopFormulaContents(version, channelSha, Right(inputs.tag))
      FormulaArtifact(inputs.base / formulaFileName, contents) :: Nil
    }
  }

  def archPackageSource(origin: FormulaOrigin): String = origin match {
    case Left(f) => s"file://${f.getAbsolutePath}"
    case Right(tag) => s"https://github.com/scalacenter/bloop/releases/download/$tag/install.py"
  }

  def generateArchBuildContents(
      version: String,
      origin: FormulaOrigin,
      channelSha: String
  ): String = {
    // Note: pkgver must only contain letters, numbers and periods to be valid
    val safeVersion = version.replace('-', '.').replace('+', '.').replace(' ', '.')

    // Replace "install.py" by a unique name to avoid conflicts with the other packages
    // and caching problems with older versions of the bloop package.
    val script = s"install-bloop-$safeVersion.py"
    val source = script + "::" + archPackageSource(origin)

    s"""# Maintainer: Guillaume Raffin <theelectronwill@gmail.com>
       |# Generator: Bloop release utilities <https://github.com/scalacenter/bloop>
       |pkgname=bloop
       |pkgver=$safeVersion
       |pkgrel=1
       |pkgdesc="Bloop gives you fast edit/compile/test workflows for Scala."
       |arch=(any)
       |url="https://scalacenter.github.io/bloop/"
       |license=('Apache')
       |depends=('java-environment>=8' 'python')
       |source=("$source")
       |sha256sums=('$channelSha')
       |
       |build() {
       |  python ./$script --dest "$$srcdir/bloop"
       |  # fix paths
       |  sed -i "s|$$srcdir/bloop|/usr/bin|g" bloop/systemd/bloop.service
       |  sed -i "s|$$srcdir/bloop/xdg|/usr/share/pixmaps|g" bloop/xdg/bloop.desktop
       |  sed -i "s|$$srcdir/bloop|/usr/lib/bloop|g" bloop/xdg/bloop.desktop
       |}
       |
       |package() {
       |  cd "$$srcdir/bloop"
       |
       |  ## binaries
       |  # we use /usr/lib/bloop so that we can add a .jvmopts file in it
       |  install -Dm755 blp-server "$$pkgdir"/usr/lib/bloop/blp-server
       |  install -Dm755 blp-coursier "$$pkgdir"/usr/lib/bloop/blp-coursier
       |  install -Dm755 bloop "$$pkgdir"/usr/lib/bloop/bloop
       |
       |  # links in /usr/bin
       |  mkdir -p "$$pkgdir/usr/bin"
       |  ln -s /usr/lib/bloop/blp-server "$$pkgdir"/usr/bin/blp-server
       |  ln -s /usr/lib/bloop/blp-coursier "$$pkgdir"/usr/bin/blp-coursier
       |  ln -s /usr/lib/bloop/bloop "$$pkgdir"/usr/bin/bloop
       |
       |  # desktop file
       |  install -Dm644 xdg/bloop.png "$$pkgdir"/usr/share/pixmaps/bloop.png
       |  install -Dm755 xdg/bloop.desktop "$$pkgdir"/usr/share/applications/bloop.desktop
       |
       |  # shell completion
       |  install -Dm644 bash/bloop "$$pkgdir"/etc/bash_completion.d/bloop
       |  install -Dm644 zsh/_bloop "$$pkgdir"/usr/share/zsh/site-functions/_bloop
       |  install -Dm644 fish/bloop.fish "$$pkgdir"/usr/share/fish/vendor_completions.d/bloop.fish
       |
       |  # systemd service
       |  install -Dm644 systemd/bloop.service "$$pkgdir"/usr/lib/systemd/user/bloop.service
       |}
       |""".stripMargin
  }

  def generateArchInfoContents(
      version: String,
      origin: FormulaOrigin,
      channelSha: String
  ): String = {
    val source = archPackageSource(origin)
    s"""pkgbase = bloop
       |pkgdesc = Bloop gives you fast edit/compile/test workflows for Scala.
       |pkgver = ${version.replace('-', '.').replace('+', '.')}
       |pkgrel = 1
       |url = https://scalacenter.github.io/bloop/
       |arch = any
       |license = Apache
       |depends = java-environment>=8
       |depends = python
       |source = $source
       |sha256sums = $channelSha
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
    val channelSha = sha256(coursierChannel)
    val pkgbuild = generateArchBuildContents(version, Left(coursierChannel), channelSha)
    val srcinfo = generateArchInfoContents(version, Left(coursierChannel), channelSha)
    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetBuild, pkgbuild)
    IO.write(targetInfo, srcinfo)
    logger.info(s"Local ArchLinux package build files created in ${versionDir.getAbsolutePath}")
  }

  val updateArchPackage = Def.task {
    val repository = "ssh://aur@aur.archlinux.org/bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val channelSha = sha256(bloopCoursierJson.value)
    val version = Keys.version.value
    val sshKey = GitUtils.authSshKey()
    cloneAndPush(repository, buildBase, version, sshKey, false) { inputs =>
      val buildFile = inputs.base / "PKGBUILD"
      val infoFile = inputs.base / ".SRCINFO"
      val buildContents = generateArchBuildContents(version, Right(inputs.tag), channelSha)
      val infoContents = generateArchInfoContents(version, Right(inputs.tag), channelSha)
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
