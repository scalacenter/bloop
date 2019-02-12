package bloop.io

import java.io.IOException
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.nio.file.{
  CopyOption,
  DirectoryNotEmptyException,
  FileSystems,
  FileVisitOption,
  FileVisitResult,
  FileVisitor,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardCopyOption,
  StandardOpenOption,
  Paths => NioPaths
}
import java.util

import bloop.logging.Logger
import io.github.soc.directories.ProjectDirectories
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, MulticastStrategy, Observable}
import sbt.io.CopyOptions

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashSet

object Paths {
  private val projectDirectories = ProjectDirectories.from("", "", "bloop")
  private def createDirFor(filepath: String): AbsolutePath =
    AbsolutePath(Files.createDirectories(NioPaths.get(filepath)))

  final val bloopCacheDir: AbsolutePath = createDirFor(projectDirectories.cacheDir)
  final val bloopDataDir: AbsolutePath = createDirFor(projectDirectories.dataDir)
  final val bloopLogsDir: AbsolutePath = createDirFor(bloopDataDir.resolve("logs").syntax)
  final val bloopConfigDir: AbsolutePath = createDirFor(projectDirectories.configDir)

  def getCacheDirectory(dirName: String): AbsolutePath = {
    val dir = bloopCacheDir.resolve(dirName)
    val dirPath = dir.underlying
    if (!Files.exists(dirPath)) Files.createDirectory(dirPath)
    else require(Files.isDirectory(dirPath), s"File '${dir.syntax}' is not a directory.")
    dir
  }

  /**
   * Get all files under `base` that match the pattern `pattern` up to depth `maxDepth`.
   *
   * Example:
   * ```
   * Paths.getAll(src, "glob:**.{scala,java}")
   * ```
   */
  def pathFilesUnder(
      base: AbsolutePath,
      pattern: String,
      maxDepth: Int = Int.MaxValue
  ): List[AbsolutePath] = {
    val out = collection.mutable.ListBuffer.empty[AbsolutePath]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)

    val visitor = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file)) out += AbsolutePath(file)
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = FileVisitResult.CONTINUE

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    val opts = util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(base.underlying, opts, maxDepth, visitor)
    out.toList
  }

  case class AttributedPath(path: AbsolutePath, lastModifiedTime: FileTime)

  /**
   * Get all files under `base` that match the pattern `pattern` up to depth `maxDepth`.
   *
   * The returned list of traversed files contains a last modified time attribute. This
   * is a duplicated version of [[pathFilesUnder]] that returns the last modified time
   * attribute for performance reasons, since the nio API exposes the attributed in the
   * visitor and minimizes the number of underlying file system calls.
   *
   * Example:
   * ```
   * Paths.getAll(src, "glob:**.{scala,java}")
   * ```
   */
  def attributedPathFilesUnder(
      base: AbsolutePath,
      pattern: String,
      logger: Logger,
      maxDepth: Int = Int.MaxValue
  ): List[AttributedPath] = {
    val out = collection.mutable.ListBuffer.empty[AttributedPath]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)

    val visitor = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file))
          out += AttributedPath(AbsolutePath(file), attributes.lastModifiedTime())
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(t: Path, e: IOException): FileVisitResult = {
        logger.error(s"Unexpected failure when visiting ${t}: '${e.getMessage}'")
        logger.trace(e)
        FileVisitResult.CONTINUE
      }

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = FileVisitResult.CONTINUE

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    if (!base.exists) Nil
    else {
      val opts = util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
      Files.walkFileTree(base.underlying, opts, maxDepth, visitor)
      out.toList
    }
  }

  /**
   * Recursively delete `path` and all its content.
   *
   * @param path The path to delete
   */
  def delete(path: AbsolutePath): Unit = {
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
    ()
  }

  def copyDirectories(origin: AbsolutePath, target: AbsolutePath, parallelUnits: Int)(
      scheduler: Scheduler
  ): Task[Unit] = {
    val (observer, observable) = Observable.multicast[(Path, Path)](
      MulticastStrategy.publish
    )(scheduler)
    Files.createDirectories(target.underlying)

    val discovery = new FileVisitor[Path] {
      var stop: Boolean = false
      var currentTargetDirectory: Path = target.underlying
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (attributes.isDirectory) FileVisitResult.CONTINUE
        else {
          val stop = observer.onNext((file, currentTargetDirectory.resolve(file.getFileName))) == monix.execution.Ack.Stop
          if (stop) FileVisitResult.TERMINATE
          else FileVisitResult.CONTINUE
        }
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.resolve(directory.getFileName)
        Files.createDirectory(currentTargetDirectory)
        FileVisitResult.CONTINUE
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.getParent()
        FileVisitResult.CONTINUE
      }
    }

    val discoverFileTree = Task {
      Files.walkFileTree(origin.underlying, discovery)
      ()
    }.doOnFinish {
      case Some(t) => Task(observer.onError(t))
      case None => Task(observer.onComplete())
    }

    val copyFilesInParallel = observable.consumeWith(Consumer.foreachParallelAsync(parallelUnits) {
      case (origin, target) =>
        Task {
          Files.copy(
            origin,
            target,
            //StandardCopyOption.COPY_ATTRIBUTES,
            LinkOption.NOFOLLOW_LINKS
          )
          ()
        }
    })

    Task.gatherUnordered(List(discoverFileTree, copyFilesInParallel)).map(_ => ())
  }

  def copyDirectoriesAsync(
      origin: AbsolutePath,
      target: AbsolutePath,
      parallelUnits: Int = 10
  ): Task[Unit] = {
    Files.createDirectories(target.underlying)
    val filesBuffer = ListBuffer.empty[(Path, Path)]
    val discovery = new FileVisitor[Path] {
      var stop: Boolean = false
      var currentTargetDirectory: Path = target.underlying
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        filesBuffer.+=((file, currentTargetDirectory.resolve(file.getFileName)))
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.resolve(directory.getFileName)
        Files.createDirectory(currentTargetDirectory)
        FileVisitResult.CONTINUE
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.getParent
        FileVisitResult.CONTINUE
      }
    }

    Files.walkFileTree(origin.underlying, discovery)
    val fileObservable = Observable.fromIterable(filesBuffer.toList)
    fileObservable.consumeWith(Consumer.foreachParallel(parallelUnits) {
      case (origin, target) =>
        Files.copy(
          origin,
          target,
          //StandardCopyOption.COPY_ATTRIBUTES,
          LinkOption.NOFOLLOW_LINKS
        )
        ()
    })
  }

  def copyFileViaTransferTo(from: Path, to: Path): Unit = {
    import java.io._
    val inputStream = new RandomAccessFile(from.toString, "rw");
    val outputStream = new RandomAccessFile(to.toString, "rw");
    val inChannel = inputStream.getChannel();
    val outChannel = outputStream.getChannel();
    inChannel.transferTo(0, inChannel.size(), outChannel);
    inChannel.close();
    outChannel.close();
    inputStream.close();
    outputStream.close();
  }

  def copyDirectoriesSequentially(origin: AbsolutePath, target: AbsolutePath): Unit = {
    Files.createDirectories(target.underlying)
    val discovery = new FileVisitor[Path] {
      var currentTargetDirectory: Path = target.underlying
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {

        val target = currentTargetDirectory.resolve(file.getFileName)
        copyFileViaTransferTo(file, target)
        /*Files.copy(
          file,
          currentTargetDirectory.resolve(file.getFileName),
          //StandardCopyOption.COPY_ATTRIBUTES,
          LinkOption.NOFOLLOW_LINKS
        )*/
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.resolve(directory.getFileName)
        Files.createDirectory(currentTargetDirectory)
        FileVisitResult.CONTINUE
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.getParent()
        FileVisitResult.CONTINUE
      }
    }

    Files.walkFileTree(origin.underlying, discovery)
    ()
  }
}
