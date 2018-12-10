package build

import java.io.File

import sbt.{Def, Keys, MessageOnlyException}
import sbt.io.syntax.fileToRichFile
import sbt.io.IO
import sbt.util.FileFunction

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
       |        <string>#{bin}/blp-server</string>
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
    cloneAndPushTag(repository, buildBase, version) { inputs =>
      val formulaFileName = "bloop.rb"
      val contents = generateHomebrewFormulaContents(version, Right(inputs.tag), installSha, false)
      FormulaArtifact(inputs.base / formulaFileName, contents)
    }
  }

  def generateScoopFormulaContents(version: String, origin: FormulaOrigin): String = {
    val url = {
      origin match {
        case Left(f) => s"""${f.toPath.toUri.toString.replace("\\", "\\\\")}"""
        case Right(tag) => s"https://github.com/scalacenter/bloop/releases/download/$tag/install.py"
      }
    }

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
  }

  val createLocalScoopFormula = Def.task {
    val logger = Keys.streams.value.log
    val version = Keys.version.value
    val versionDir = Keys.target.value / version
    val installScript = versionedInstallScript.value

    val formulaFileName = "bloop.json"
    val targetLocalFormula = versionDir / formulaFileName
    val contents = generateScoopFormulaContents(version, Left(installScript))

    if (!versionDir.exists()) IO.createDirectory(versionDir)
    IO.write(targetLocalFormula, contents)
    logger.info(s"Local Scoop formula created in ${targetLocalFormula.getAbsolutePath}")
  }

  val updateScoopFormula = Def.task {
    val repository = "https://github.com/scalacenter/scoop-bloop.git"
    val buildBase = BuildKeys.buildBase.value
    val version = Keys.version.value
    cloneAndPushTag(repository, buildBase, version) { inputs =>
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

  case class FormulaInputs(tag: String, base: File)
  case class FormulaArtifact(target: File, contents: String)

  private final val bloopoidName = "Bloopoid"
  private final val bloopoidEmail = "bloop@trashmail.ws"
  def cloneAndPushTag(repository: String, buildBase: File, version: String)(
      generateFormula: FormulaInputs => FormulaArtifact
  ): Unit = {
    val token = sys.env.get("BLOOPOID_GITHUB_TOKEN").getOrElse {
      throw new MessageOnlyException("Couldn't find Github oauth token in `BLOOPOID_GITHUB_TOKEN`")
    }
    val tagName = GitUtils.withGit(buildBase)(GitUtils.latestTagIn(_)).getOrElse {
      throw new MessageOnlyException("No tag found in this repository.")
    }

    IO.withTemporaryDirectory { homebrewBase =>
      GitUtils.clone(repository, homebrewBase, token) { homebrewRepo =>
        val commitMessage = s"Updating to Bloop $tagName"
        val artifact = generateFormula(FormulaInputs(tagName, homebrewBase))
        IO.write(artifact.target, artifact.contents)
        val changed = artifact.target.getName :: Nil
        GitUtils.commitChangesIn(
          homebrewRepo,
          changed,
          commitMessage,
          bloopoidName,
          bloopoidEmail
        )
        GitUtils.tag(homebrewRepo, tagName, commitMessage)
        GitUtils.push(homebrewRepo, "origin", Seq("master", tagName), token)
      }
    }
  }

  def sha256(file: sbt.File): String = {
    import org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256
    import org.apache.commons.codec.digest.DigestUtils
    new DigestUtils(SHA_256).digestAsHex(file)
  }
}
