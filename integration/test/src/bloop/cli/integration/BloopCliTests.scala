package bloop.cli.integration

import com.eed3si9n.expecty.Expecty.expect

class BloopCliTests extends munit.FunSuite {

  test("start / stop Bloop") {
    TmpDir.fromTmpDir { root =>
      val dirArgs = Seq[os.Shellable]("--daemon-dir", root / "daemon")

      os.proc(Launcher.launcher, "exit", dirArgs)
        .call(cwd = root, stdin = os.Inherit, stdout = os.Inherit)
      val statusCheck0 = os.proc(Launcher.launcher, "status", dirArgs)
        .call(cwd = root)
        .out.trim()
      expect(statusCheck0 == "stopped")

      os.proc(Launcher.launcher, dirArgs, "about")
        .call(cwd = root, stdin = os.Inherit, stdout = os.Inherit, env = Launcher.extraEnv)
      val statusCheck1 = os.proc(Launcher.launcher, "status", dirArgs)
        .call(cwd = root)
        .out.trim()
      expect(statusCheck1 == "running")

      os.proc(Launcher.launcher, "exit", dirArgs)
        .call(cwd = root, stdin = os.Inherit, stdout = os.Inherit)
      val statusCheck2 = os.proc(Launcher.launcher, "status", dirArgs)
        .call(cwd = root)
        .out.trim()
      expect(statusCheck2 == "stopped")
    }
  }
}
