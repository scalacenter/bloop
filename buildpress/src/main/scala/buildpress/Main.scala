package buildpress

import buildpress.io.AbsolutePath
import bloop.launcher.core.Shell

object Main
    extends Buildpress(
      System.in,
      System.out,
      System.err,
      Shell.default,
      None,
      AbsolutePath.workingDirectory
    ) {
  def main(args: Array[String]): Unit = run(args)
  def exit(exitCode: Int): Unit = System.exit(exitCode)
}
