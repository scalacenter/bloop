package buildpress

import bloop.io.AbsolutePath

object Main
    extends Buildpress(
      System.out,
      System.err,
      AbsolutePath.workingDirectory
    ) {
  def main(args: Array[String]): Unit = run(args)
  def exit(exitCode: Int): Unit = System.exit(exitCode)
}
