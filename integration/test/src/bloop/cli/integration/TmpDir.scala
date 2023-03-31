package bloop.cli.integration

import java.util.concurrent.atomic.AtomicInteger
import java.security.SecureRandom

object TmpDir {

  private lazy val baseTmpDir = Option(System.getenv("BLOOP_CLI_TESTS_TMP_DIR"))
    .map { p =>
      val base   = os.Path(p, os.pwd)
      val random = math.abs(new SecureRandom().nextInt().toLong)
      base / s"run-$random"
    }
    .getOrElse {
      sys.error("BLOOP_CLI_TESTS_TMP_DIR not set")
    }

  private val count = new AtomicInteger

  def fromTmpDir[T](f: os.Path => T): T = {
    val tmpDir = baseTmpDir / s"tmp-${count.incrementAndGet()}"
    try {
      os.makeDir.all(tmpDir)
      f(tmpDir)
    }
    finally os.remove.all(tmpDir)
  }

}
