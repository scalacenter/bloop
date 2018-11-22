package bloop.config.util

import java.nio.file.{Path, Files}

object ConfigUtil {
  def pathsOutsideRoots(roots: Seq[Path], paths: Seq[Path]): Seq[Path] = {
    paths.filterNot { path =>
      roots.exists { root =>
        var found: Boolean = false
        val rootDirSize = root.toString.size
        var currentTarget = (if (Files.isRegularFile(path)) path.getParent else path).toAbsolutePath
        while (!found &&
               currentTarget != null &&
               // Use a heuristic to know if we should short-circuit and return false
               currentTarget.toString.size >= rootDirSize) {
          if (currentTarget == root) {
            found = true
          }

          currentTarget = currentTarget.getParent
        }
        found
      }
    }
  }
}
