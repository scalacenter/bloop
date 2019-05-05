package buildpress.io
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException

object BuildpressPaths {
  val UserHome: AbsolutePath = {
    Option(System.getenv("HOME"))
      .filterNot(_.isEmpty)
      .map(AbsolutePath(_))
      .getOrElse(AbsolutePath(System.getProperty("user.home")))
  }

  def delete(path: AbsolutePath): Unit = {
    try {
      Files.walkFileTree(
        path.underlying,
        new SimpleFileVisitor[Path] {
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            Files.delete(file)
            FileVisitResult.CONTINUE
          }

          override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
            try Files.delete(dir)
            catch { case _: DirectoryNotEmptyException => () } // Happens sometimes on Windows?
            FileVisitResult.CONTINUE
          }
        }
      )
    } catch { case _: IOException => () }
    ()
  }
}
