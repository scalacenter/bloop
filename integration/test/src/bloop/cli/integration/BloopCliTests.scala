package bloop.cli.integration

import com.eed3si9n.expecty.Expecty.expect

class BloopCliTests extends munit.FunSuite {

  test("start / stop Bloop") {
    TmpDir.fromTmpDir { root =>
      val dirArgs = Seq[os.Shellable]("--daemon-dir", root / "daemon")

      os.proc(Launcher.launcher, "exit", dirArgs)
        .call(cwd = root)
      val statusCheck0 = os.proc(Launcher.launcher, "status", dirArgs)
        .call(cwd = root)
        .out.trim()
      expect(statusCheck0 == "stopped")

      val res = os.proc(Launcher.launcher, dirArgs, "about")
        .call(cwd = root)
      expect(res.out.text().startsWith("bloop v"))
      val statusCheck1 = os.proc(Launcher.launcher, "status", dirArgs)
        .call(cwd = root)
        .out.trim()
      expect(statusCheck1 == "running")

      os.proc(Launcher.launcher, "exit", dirArgs)
        .call(cwd = root)
      val statusCheck2 = os.proc(Launcher.launcher, "status", dirArgs)
        .call(cwd = root)
        .out.trim()
      expect(statusCheck2 == "stopped")
    }
  }
}
