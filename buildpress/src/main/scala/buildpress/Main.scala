package buildpress

import buildpress.io.AbsolutePath
import bloop.launcher.core.Shell
import buildpress.internal.build.BuildpressInfo

object Main
    extends Buildpress(
      BuildpressInfo.version,
      System.in,
      System.out,
      System.err,
      Shell.default,
      None,
      AbsolutePath.workingDirectory
    ) {
  def main(args: Array[String]): Unit = run()
  def exit(exitCode: Int): Unit = ()
}
