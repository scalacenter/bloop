class Bloop < Formula
  desc "Bloop gives you fast edit/compile/test workflows for Scala."
  homepage "https://github.com/scalacenter/bloop"
  version "#BLOOP_LATEST_RELEASE#"
  url "https://raw.githubusercontent.com/scalacenter/bloop/#BLOOP_LATEST_TAG#/bin/install.py"
  sha256 "#BLOOP_INSTALL_PY_SHA256#"
  bottle :unneeded

  depends_on "python"
  depends_on :java => "1.8+"

  def install
      mkdir "bin"
      system "python2", "install.py", "--dest", "bin", "--version", version
      prefix.install "bin"
  end

  test do
  end
end

