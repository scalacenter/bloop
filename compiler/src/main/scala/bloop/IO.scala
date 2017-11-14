package bloop

import java.nio.file._
import java.nio.file.attribute._
import java.io.IOException

object IO {

  def getAll(base: Path, pattern: String): Array[Path] = {
    val out     = collection.mutable.ArrayBuffer.empty[Path]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)
    val visitor = new FileVisitor[Path] {
      override def preVisitDirectory(directory: Path,
                                     attributes: BasicFileAttributes): FileVisitResult =
        FileVisitResult.CONTINUE

      override def postVisitDirectory(directory: Path, exception: IOException): FileVisitResult =
        FileVisitResult.CONTINUE

      override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file)) out += file.toAbsolutePath
        FileVisitResult.CONTINUE
      }

      override def visitFileFailed(file: Path, exception: IOException): FileVisitResult =
        FileVisitResult.CONTINUE
    }
    Files.walkFileTree(base, visitor)
    out.toArray
  }

}
