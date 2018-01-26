package build

import java.io.File

import sbt.{Def, Keys, MessageOnlyException}
import sbt.io.syntax.fileToRichFile
import sbt.io.IO

/** Utilities that are useful for releasing Bloop */
object ReleaseUtils {

  /** The path to our installation script */
  private val installScript = Def.setting { BuildKeys.buildBase.value / "bin" / "install.py" }

  /** The latest commit in the `nailgun` submodule. */
  private val nailgunCommit = Def.setting {
    GitUtils.withGit(GitUtils.submoduleGitDir("nailgun").value) { git =>
      GitUtils.latestCommitIn(git)
    }
  }

  /**
   * Creates a new installation script (based on the normal installation script) that has default
   * values for the nailgun commit and version of Bloop to install.
   *
   * This lets us create an installation script that doesn't need any additional input to install
   * the version of Bloop that we're releasing.
   */
  val versionedInstallScript = Def.task {
    IO.readLines(installScript.value) match {
      case shebang :: rest =>
        val scriptTarget = Keys.target.value / "install.py"
        val customizedVariables =
          List(s"""NAILGUN_COMMIT = "${nailgunCommit.value}"""",
               s"""BLOOP_VERSION = "${Keys.version.value}"""")
        val newContent = shebang :: customizedVariables ++ rest
        IO.writeLines(scriptTarget, newContent)
        scriptTarget
      case _ =>
        sys.error(installScript.value.getAbsolutePath + " was empty?")
    }
  }

  /**
   * The content of the Homebrew Formula to install the version of Bloop that we're releasing.
   *
   * @param version The version of Bloop that we're releasing (usually `Keys.version.value`)
   * @param tagName The name of the tag that we're releasing
   * @param installSha The SHA-256 of the versioned installation script.
   */
  def formulaContent(version: String, tagName: String, installSha: String): String = {
    s"""class Bloop < Formula
       |  desc "Bloop gives you fast edit/compile/test workflows for Scala."
       |  homepage "https://github.com/scalacenter/bloop"
       |  version "$version"
       |  url "https://github.com/scalacenter/bloop/releases/download/$tagName/install.py"
       |  sha256 "$installSha"
       |  bottle :unneeded
       |
       |  depends_on "python"
       |  depends_on :java => "1.8+"
       |
       |  def install
       |      mkdir "bin"
       |      system "python2", "install.py", "--dest", "bin", "--version", version
       |      prefix.install "bin"
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
       |        <string>#{bin}/bloop-server</string>
       |    </array>
       |    <key>KeepAlive</key>
       |    <true/>
       |</dict>
       |</plist>
       |          EOS
       |      end
       |
       |  test do
       |  end
       |end""".stripMargin
  }

  /** Generate the new Homebrew formula, a new tag and push all that in our Homebrew tap */
  val updateHomebrewFormula = Def.task {
    val buildBase = BuildKeys.buildBase.value
    val installSha = sha256(versionedInstallScript.value)
    val version = Keys.version.value
    val tagName = GitUtils.withGit(buildBase)(GitUtils.latestTagIn(_)).getOrElse {
      throw new MessageOnlyException("No tag found in this repository.")
    }

    IO.withTemporaryDirectory { homebrewBase =>
      GitUtils.clone("git@github.com:scalacenter/homebrew-bloop.git", homebrewBase) {
        homebrewRepo =>
          val formulaFileName = "bloop.rb"
          val commitMessage = s"Updating to Bloop $tagName"
          val content = formulaContent(version, tagName, installSha)
          IO.write(homebrewBase / formulaFileName, content)
          val changed = formulaFileName :: Nil
          GitUtils.commitChangesIn(homebrewRepo, changed, commitMessage)
          GitUtils.push(homebrewRepo, "origin", "master", tagName)
      }
    }
  }

  private def sha256(file: sbt.File): String = {
    import java.nio.file.Files
    import java.security.MessageDigest
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = Files.readAllBytes(file.toPath)
    val hash = digest.digest(bytes)
    val hexString = new StringBuilder()
    hash.foreach { byte =>
      val hex = Integer.toHexString(0xff & byte)
      if (hex.length == 1) hexString.append('0')
      else hexString.append(hex)
    }

    hexString.toString

  }
}
