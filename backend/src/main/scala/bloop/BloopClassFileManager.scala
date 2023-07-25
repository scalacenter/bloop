package bloop

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode
import bloop.io.{Paths => BloopPaths}
import bloop.task.Task
import bloop.tracing.BraveTracer

import xsbti.compile.ClassFileManager

final class BloopClassFileManager(
    backupDir0: Path,
    inputs: CompileInputs,
    outPaths: CompileOutPaths,
    allGeneratedRelativeClassFilePaths: mutable.HashMap[String, File],
    readOnlyCopyDenylist: mutable.HashSet[Path],
    allInvalidatedClassFilesForProject: mutable.HashSet[File],
    allInvalidatedExtraCompileProducts: mutable.HashSet[File],
    backgroundTasksWhenNewSuccessfulAnalysis: mutable.ListBuffer[CompileBackgroundTasks.Sig],
    backgroundTasksForFailedCompilation: mutable.ListBuffer[CompileBackgroundTasks.Sig]
) extends ClassFileManager {
  private[this] val readOnlyClassesDir = outPaths.internalReadOnlyClassesDir.underlying
  private[this] val readOnlyClassesDirPath = readOnlyClassesDir.toString
  private[this] val newClassesDir = outPaths.internalNewClassesDir.underlying
  private[this] val newClassesDirPath = newClassesDir.toString
  private[this] val dependentClassFilesLinks = new mutable.HashSet[Path]()
  private[this] val weakClassFileInvalidations = new mutable.HashSet[Path]()
  private[this] val generatedFiles = new mutable.HashSet[File]

  // Supported compile products by the class file manager
  private[this] val supportedCompileProducts = List(".sjsir", ".nir", ".tasty")
  // Files backed up during compilation
  private[this] val movedFiles = new mutable.HashMap[File, File]

  private val backupDir = backupDir0.normalize
  backupDir.toFile.delete()
  Files.createDirectories(backupDir)

  /**
   * Returns the set of all invalidated class files.
   *
   * This method is called once per iteration by the Scala compiler but can be
   * called more than once by the Java compiler, hence its memoization.
   */
  def invalidatedClassFilesSet: mutable.HashSet[File] = {
    allInvalidatedClassFilesForProject
  }

  private[this] val invalidatedClassFilesInDependentProjects: Set[File] = {
    inputs.invalidatedClassFilesInDependentProjects --
      inputs.generatedClassFilePathsInDependentProjects.valuesIterator
  }

  private[this] var memoizedInvalidatedClassFiles: Array[File] = _
  def invalidatedClassFiles(): Array[File] = {
    if (memoizedInvalidatedClassFiles == null) {
      // Weak invalidated class files are class files that have been found in
      // dependent projects and should not be communicated to the compiler
      val skippedInvalidations =
        weakClassFileInvalidations.iterator.map(_.toFile)

      memoizedInvalidatedClassFiles = {
        (allInvalidatedClassFilesForProject.toSet ++
          invalidatedClassFilesInDependentProjects) -- skippedInvalidations
      }.toArray
    }

    memoizedInvalidatedClassFiles
  }

  def delete(classes: Array[File]): Unit = {
    memoizedInvalidatedClassFiles = null

    classes.foreach { classFile =>
      val classFilePath = classFile.toPath
      // Return invalidated class file right away if it's in the new classes dir
      if (!classFilePath.startsWith(newClassesDir)) {
        /*
         * The invalidated class file comes from the read-only classes
         * directory. Check that the user hasn't moved the original source to a
         * dependent project and that the same relative class file doesn't
         * exist in dependent projects before invalidating the file (and its
         * symbol).
         *
         * Why do we do this? If we would invalidate class file `b/Foo.class`
         * as usual and now `Foo` existed in a dependent classes file
         * `a/Foo.class`, the compiler would try to load the invalidated class
         * file in `b/Foo.class`, find that it's invalidated and skip it.
         * However, the search for that symbol would not continue and would
         * never hit the dependent class file. This would result in a "not
         * found type" error in the compiler. To avoid this error, we skip the
         * invalidated class files in this project if they exist in a dependent
         * project and instead create a symbolic link in the new classes directory
         * to the dependent class file such that the dependent class file takes
         * precedence over the invalidated class file in this project and it's
         * loaded by the compiler.
         *
         * For this strategy to work, we also need to make sure we never copy
         * neither the links nor the invalidated class files when we aggregate
         * the contents of the new classes directory and the read-only classes
         * directory, which we do here as well as in [[bloop.Compiler]].
         */

        val relativeFilePath = readOnlyClassesDir.relativize(classFilePath).toString

        BloopClasspathEntryLookup.definedClassFileInDependencies(
          relativeFilePath,
          inputs.dependentResults
        ) match {
          case None => ()
          case Some(foundClassFilePath) =>
            weakClassFileInvalidations.+=(classFilePath)
            val newLink = newClassesDir.resolve(relativeFilePath)
            BloopClassFileManager.link(newLink, foundClassFilePath) match {
              case Success(_) => dependentClassFilesLinks.+=(newLink)
              case Failure(exception) =>
                inputs.logger.error(
                  s"Failed to create link for invalidated file $foundClassFilePath: ${exception.getMessage()}"
                )
                inputs.logger.trace(exception)
            }
            ()
        }
      }
    }
    allInvalidatedClassFilesForProject.++=(classes)

    val invalidatedExtraCompileProducts = classes.flatMap { classFile =>
      val prefixClassName = classFile.getName().stripSuffix(".class")
      supportedCompileProducts.flatMap { supportedProductSuffix =>
        val productName = prefixClassName + supportedProductSuffix
        val productAssociatedToClassFile = new File(classFile.getParentFile, productName)
        if (!productAssociatedToClassFile.exists()) Nil
        else List(productAssociatedToClassFile)
      }
    }

    // Idea taken from the default TransactionalClassFileManager in zinc
    // https://github.com/sbt/zinc/blob/c18637c1b30f8ab7d1f702bb98301689ec75854b/internal/zinc-core/src/main/scala/sbt/internal/inc/ClassFileManager.scala#L183
    val toBeBackedUp = (classes ++ invalidatedExtraCompileProducts).filter(c =>
      !movedFiles.contains(c) && !generatedFiles(c)
    )
    for {
      c <- toBeBackedUp
      if c.exists()
    } movedFiles.put(c, move(c)).foreach(move)

    for {
      f <- classes
      if f.exists()
    } f.delete()

    allInvalidatedExtraCompileProducts.++=(invalidatedExtraCompileProducts)
  }

  def generated(generatedClassFiles: Array[File]): Unit = {
    memoizedInvalidatedClassFiles = null
    generatedClassFiles.foreach { generatedClassFile =>
      generatedFiles += generatedClassFile
      val newClassFile = generatedClassFile.getAbsolutePath
      val relativeClassFilePath = newClassFile.replace(newClassesDirPath, "")
      allGeneratedRelativeClassFilePaths.put(relativeClassFilePath, generatedClassFile)
      val rebasedClassFile =
        new File(newClassFile.replace(newClassesDirPath, readOnlyClassesDirPath))
      // Delete generated class file + rebased class file because
      // invalidations can happen in both paths, no-op if missing
      allInvalidatedClassFilesForProject.-=(generatedClassFile)
      allInvalidatedClassFilesForProject.-=(rebasedClassFile)

      def productFile(classFile: File, supportedProductSuffix: String) = {
        val productName = classFile
          .getName()
          .stripSuffix(".class") + supportedProductSuffix
        new File(classFile.getParentFile, productName)
      }
      supportedCompileProducts.foreach { supportedProductSuffix =>
        val generatedProductName = productFile(generatedClassFile, supportedProductSuffix)
        val rebasedProductName = productFile(rebasedClassFile, supportedProductSuffix)

        if (generatedProductName.exists() || rebasedProductName.exists()) {
          allInvalidatedExtraCompileProducts.-=(generatedProductName)
          allInvalidatedExtraCompileProducts.-=(rebasedProductName)
        }
      }
    }

  }

  def complete(success: Boolean): Unit = {

    val deleteAfterCompilation = Task { BloopPaths.delete(AbsolutePath(backupDir)) }
    if (success) {
      dependentClassFilesLinks.foreach { unnecessaryClassFileLink =>
        Files.deleteIfExists(unnecessaryClassFileLink)
      }

      // Schedule copying compilation products to visible classes directory
      backgroundTasksWhenNewSuccessfulAnalysis.+=(
        (
            clientExternalClassesDir: AbsolutePath,
            _,
            clientTracer: BraveTracer
        ) => {
          clientTracer.traceTaskVerbose("copy new products to external classes dir") { _ =>
            val config = ParallelOps.CopyConfiguration(5, CopyMode.ReplaceExisting, Set.empty)
            ParallelOps
              .copyDirectories(config)(
                newClassesDir,
                clientExternalClassesDir.underlying,
                inputs.ioScheduler,
                enableCancellation = false,
                inputs.logger
              )
              .map { walked =>
                readOnlyCopyDenylist.++=(walked.target)
                ()
              }
              .flatMap(_ => deleteAfterCompilation)
          }
        }
      )
    } else {
      /* Restore all files from backuped last successful compilation to make sure
       * that they are still available.
       */
      for {
        (orig, tmp) <- movedFiles
        if tmp.exists
      } {
        if (!orig.getParentFile.exists) {
          Files.createDirectory(orig.getParentFile.toPath())
        }
        Files.move(tmp.toPath(), orig.toPath())
      }
      backupDir.toFile().delete()
      ()

      // Delete all compilation products generated in the new classes directory
      val deleteNewDir = Task { BloopPaths.delete(AbsolutePath(newClassesDir)); () }.memoize
      backgroundTasksForFailedCompilation.+=((_, _, clientTracer: BraveTracer) => {
        clientTracer.traceTask("delete class files after")(_ =>
          deleteNewDir.flatMap(_ => deleteAfterCompilation)
        )
      })

      backgroundTasksForFailedCompilation.+=(
        (
            clientExternalClassesDir: AbsolutePath,
            _,
            clientTracer: BraveTracer
        ) =>
          Task.defer {
            // Exclude dirs because process controlling external dir might have created empty dir layouts
            val externalDirHasFiles =
              !BloopPaths.isDirectoryEmpty(clientExternalClassesDir, excludeDirs = true)
            val isInternalEmpty =
              BloopPaths.isDirectoryEmpty(outPaths.internalReadOnlyClassesDir, excludeDirs = false)

            if (externalDirHasFiles) Task.unit
            else if (isInternalEmpty) Task.unit
            else
              clientTracer.traceTask("populate empty classes dir") { _ =>
                // Prepopulate external classes dir even though compilation failed
                val config = ParallelOps.CopyConfiguration(1, CopyMode.NoReplace, Set.empty)
                ParallelOps
                  .copyDirectories(config)(
                    Paths.get(readOnlyClassesDirPath),
                    clientExternalClassesDir.underlying,
                    inputs.ioScheduler,
                    enableCancellation = false,
                    inputs.logger
                  )
                  .map(_ => ())
              }
          }
      )
    }

  }

  private def move(c: File): File = {
    val target = Files.createTempFile(backupDir, "bloop", ".class").toFile
    Files.move(c.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    target
  }
}

object BloopClassFileManager {
  def link(link: Path, target: Path): Try[Unit] = {
    Try {
      // Make sure parent directory for link exists
      Files.createDirectories(link.getParent)
      // Try symbolic link before hard link
      try Files.createSymbolicLink(link, target)
      catch {
        case _: IOException =>
          Files.createLink(link, target)
      }
      ()
    }
  }
}
