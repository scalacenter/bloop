package bloop.config.util

import java.nio.file.Path

object ConfigUtil {
  def pathsOutsideRoots(roots: Seq[Path], paths: Seq[Path]): Seq[Path] = {
    for {
      path <- paths
      root <- roots if !path.toAbsolutePath.startsWith(root)
    } yield {
      path
    }
  }
}
