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
  private val installScript = Def.setting { BuildKeys.buildBase.value / "bin" / "install.py" }

  /**
   * Creates a new installation script (based on the normal installation script) that has default
   * values for the nailgun commit and version of Bloop to install.
   *
   * This lets us create an installation script that doesn't need any additional input to install
   * the version of Bloop that we're releasing.
   */
  val versionedInstallScript = Def.task {
    val nailgun = Dependencies.nailgunCommit
    val coursier = Dependencies.coursierVersion
    val version = Keys.version.value
    val target = Keys.target.value
    val log = Keys.streams.value.log
    val cacheDirectory = Keys.streams.value.cacheDirectory
    val cachedWrite =
      FileFunction.cached(cacheDirectory) { scripts =>
        scripts.map { script =>
          val lines = IO.readLines(script)
          val marker = "# INSERT_INSTALL_VARIABLES"
          lines.span(_ != marker) match {
            case (before, _ :: after) =>
              val customizedVariables =
                List(
                  s"""NAILGUN_COMMIT = "$nailgun"""",
                  s"""BLOOP_VERSION = "$version"""",
                  s"""COURSIER_VERSION = "$coursier""""
                )
              val newContent = before ::: customizedVariables ::: after
              val scriptTarget = target / script.getName
              IO.writeLines(scriptTarget, newContent)
              scriptTarget

            case _ =>
              sys.error(s"Couldn't find '$marker' in '$script'.")
          }
        }
      }
    cachedWrite(Set(installScript.value)).head
  }

  /* Defines an origin where the left is a path to a local file and the right a tag name. */
  type FormulaOrigin = Either[File, String]

  /**
   * The content of the Homebrew Formula to install the version of Bloop that we're releasing.
   *
   * @param version The version of Bloop that we're releasing (usually `Keys.version.value`)
   * @param origin The origin where we install the homebrew formula from.
   * @param installSha The SHA-256 of the versioned installation script.
   */
  def generateHomebrewFormulaContents(
      version: String,
      origin: FormulaOrigin,
      installSha: String,
      local: Boolean
  ): String = {
    val url = {
      origin match {
        case Left(f) => s"""url "file://${f.getAbsolutePath}""""
        case Right(tagName) =>
          s"""url "https://github.com/scalacenter/bloop/releases/download/$tagName/install.py""""
      }
    }

    val pythonInvocation = {
      if (!local) """system "python3", "install.py", "--dest", "bin", "--version", version"""
      else {
        val cwd = sys.props("user.dir")
        val ivyHome = new File(sys.props("user.home")) / ".ivy2/"
        s"""system "python3", "install.py", "--dest", "bin", "--version", version, "--ivy-home", "$ivyHome", "--bloop-home", "$cwd""""
      }
    }

    s"""class Bloop < Formula
       |  desc "Bloop gives you fast edit/compile/test workflows for Scala."
       |  homepage "https://github.com/scalacenter/bloop"
       |  version "$version"
       |  $url
       |  sha256 "$installSha"
       |  bottle :unneeded
       |
       |  depends_on "bash-completion"
       |  depends_on "python3"
       |  depends_on :java => "1.8+"
       |
       |  def install
       |      mkdir "bin"
       |      ${pythonInvocation}
       |      zsh_completion.install "bin/zsh/_bloop"
       |      bash_completion.install "bin/bash/bloop"
       |      fish_completion.install "bin/fish/bloop.fish"
       |
       |      # We need to create these files manually here, because otherwise launchd
       |      # will create them with owner set to `root` (see the plist file below).
       |      FileUtils.mkdir_p("log/bloop/")
       |      FileUtils.touch("log/bloop/bloop.out.log")
       |      FileUtils.touch("log/bloop/bloop.err.log")
       |
       |      prefix.install "bin"
       |      prefix.install "log"
       |  end
       |
       |  def plist; <<~EOS
       |<?xml version="1.0" encoding="UTF-8"?>
       |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
       |<plist version="1.0">
       |<dict>
       |    <key>Label</key>
       |    <string>#{plist_name}</string>
       |    <key>ProgramArguments</key>
       |    <array>
       |        <string>#{bin}/bloop</string>
       |        <string>server</string>
       |    </array>
       |    <key>KeepAlive</key>
       |    <true/>
       |    <key>StandardOutPath</key>
       |    <string>#{prefix}/log/bloop/bloop.out.log</string>
       |    <key>StandardErrorPath</key>
       |    <string>#{prefix}/log/bloop/bloop.err.log</string>
       |</dict>
       |</plist>
       |          EOS
       |      end
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
    val installScript = versionedInstallScript.value
    val installSha = sha256(installScript)
    val contents = generateHomebrewFormulaContents(version, Left(installScript), installSha, true)
    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetLocalFormula, contents)
    logger.info(s"Local Homebrew formula created in ${targetLocalFormula.getAbsolutePath}")
  }

  /** Generate the new Homebrew formula, a new tag and push all that in our Homebrew tap */
  val updateHomebrewFormula = Def.task {
    val repository = "https://github.com/scalacenter/homebrew-bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val installSha = sha256(versionedInstallScript.value)
    val version = Keys.version.value
    val token = GitUtils.authToken()
    cloneAndPushTag(repository, buildBase, version, token) { inputs =>
      val formulaFileName = "bloop.rb"
      val contents = generateHomebrewFormulaContents(version, Right(inputs.tag), installSha, false)
      FormulaArtifact(inputs.base / formulaFileName, contents)
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
       |    "HOME": "$$dir",
       |    "SCOOP": "true"
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
    val installScript = versionedInstallScript.value
    val installSha = sha256(installScript)

    val formulaFileName = "bloop.json"
    val targetLocalFormula = versionDir / formulaFileName
    val contents = generateScoopFormulaContents(version, installSha, Left(installScript))

    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetLocalFormula, contents)
    logger.info(s"Local Scoop formula created in ${targetLocalFormula.getAbsolutePath}")
  }

  val updateScoopFormula = Def.task {
    val repository = "https://github.com/scalacenter/scoop-bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val version = Keys.version.value
    val token = GitUtils.authToken()
    cloneAndPushTag(repository, buildBase, version, token) { inputs =>
      val formulaFileName = "bloop.json"
      val url = s"https://github.com/scalacenter/bloop/releases/download/${inputs.tag}/install.py"
      val contents =
        s"""{
           |  "version": "$version",
           |  "url": "$url",
           |  "depends": "python",
           |  "bin": "bloop.cmd",
           |  "env_add_path": "$$dir",
           |  "env_set": {
           |    "HOME": "$$dir",
           |    "SCOOP": "true"
           |  },
           |  "installer": {
           |    "script": "python $$dir/install.py --dest $$dir"
           |  }
           |}
        """.stripMargin
      FormulaArtifact(inputs.base / formulaFileName, contents)
    }
  }

  def archPackageSource(origin: FormulaOrigin): String = origin match {
    case Left(f) => s"file://${f.getAbsolutePath}"
    case Right(tag) => s"https://github.com/scalacenter/bloop/releases/download/$tag/install.py"
  }

  def generateArchBuildContents(
      version: String,
      origin: FormulaOrigin,
      installSha: String
  ): String = {
    val source = archPackageSource(origin)
    // Note: pkgver must only contain letters, numbers and periods to be valid
    s"""pkgname=bloop
       |pkgver=${version.replace('-', '.').replace('+', '.')}
       |pkgrel=1
       |pkgdesc="Bloop gives you fast edit/compile/test workflows for Scala."
       |arch=(any)
       |url="https://scalacenter.github.io/bloop/"
       |license=('Apache')
       |depends=('scala' 'python')
       |source=("$source")
       |sha256sums=('$installSha')
       |
       |build() {
       |  python ./install.py --dest "$$srcdir/bloop"
       |  # fix paths
       |  sed -i "s|$$srcdir/bloop|/usr/bin|g" bloop/systemd/bloop.service
       |  sed -i "s|$$srcdir/bloop/xdg|/usr/share/pixmaps|g" bloop/xdg/bloop.desktop
       |  sed -i "s|$$srcdir/bloop|/usr/bin|g" bloop/xdg/bloop.desktop
       |}
       |
       |package() {
       |  cd "$$srcdir/bloop"
       |
       |  # binaries
       |  install -Dm755 blp-server "$$pkgdir"/usr/bin/blp-server
       |  install -Dm755 blp-coursier "$$pkgdir"/usr/bin/blp-coursier
       |  install -Dm755 bloop "$$pkgdir"/usr/bin/bloop
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
    """.stripMargin
  }

  def generateArchInfoContents(
      version: String,
      origin: FormulaOrigin,
      installSha: String
  ): String = {
    val source = archPackageSource(origin)
    s"""pkgname=bloop
       |pkgdesc=Bloop gives you fast edit/compile/test workflows for Scala.
       |pkgver=${version.replace('-', '.').replace('+', '.')}
       |pkgrel=1
       |url=https://scalacenter.github.io/bloop/
       |arch=any
       |license=Apache
       |depends=scala
       |depends=python
       |source=$source
       |sha256sums=$installSha
    """.stripMargin
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
    val installScript = versionedInstallScript.value
    val installSha = sha256(installScript)
    val pkgbuild = generateArchBuildContents(version, Left(installScript), installSha)
    val srcinfo = generateArchInfoContents(version, Left(installScript), installSha)
    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetBuild, pkgbuild)
    IO.write(targetInfo, srcinfo)
    logger.info(s"Local ArchLinux package build files created in ${versionDir.getAbsolutePath}")
  }

  case class FormulaInputs(tag: String, base: File)
  case class FormulaArtifact(target: File, contents: String)

  private final val bloopoidName = "Bloopoid"
  private final val bloopoidEmail = "bloop@trashmail.ws"
  def cloneAndPushTag(repository: String, buildBase: File, version: String, auth: GitAuth)(
      generateFormula: FormulaInputs => FormulaArtifact
  ): Unit = {
    val tagName = GitUtils.withGit(buildBase)(GitUtils.latestTagIn(_)).getOrElse {
      throw new MessageOnlyException("No tag found in this repository.")
    }
    IO.withTemporaryDirectory { tmpDir =>
      GitUtils.clone(repository, tmpDir, auth) { gitRepo =>
        val commitMessage = s"Updating to Bloop $tagName"
        val artifact = generateFormula(FormulaInputs(tagName, tmpDir))
        IO.write(artifact.target, artifact.contents)
        val changed = artifact.target.getName :: Nil
        GitUtils.commitChangesIn(
          gitRepo,
          changed,
          commitMessage,
          bloopoidName,
          bloopoidEmail
        )
        GitUtils.tag(gitRepo, tagName, commitMessage)
        GitUtils.push(gitRepo, "origin", Seq("master", tagName), auth)
      }
    }
  }

  def sha256(file: sbt.File): String = {
    import org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256
    import org.apache.commons.codec.digest.DigestUtils
    new DigestUtils(SHA_256).digestAsHex(file)
  }
}
