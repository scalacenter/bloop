package bloop

import bloop.io.{Paths => BloopPaths}
import bloop.io.AbsolutePath
import bloop.tracing.BraveTracer
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.mutable

import xsbti.compile.ClassFileManager
import monix.eval.Task
import bloop.reporter.Reporter
import xsbti.compile.PreviousResult
import java.nio.file.Files
import java.io.IOException
import scala.util.Try
import scala.util.Failure
import scala.util.Success

final class BloopClassFileManager(
    inputs: CompileInputs,
    outPaths: CompileOutPaths,
    allGeneratedRelativeClassFilePaths: mutable.HashMap[String, File],
    readOnlyCopyBlacklist: mutable.HashSet[Path],
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

  // Supported compile products by the class file manager
  private[this] val supportedCompileProducts = List(".sjsir", ".nir", ".tasty")

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
          case Some(foundClassFile) =>
            weakClassFileInvalidations.+=(classFilePath)
            val newLink = newClassesDir.resolve(relativeFilePath)
            BloopClassFileManager.link(newLink, foundClassFile.toPath) match {
              case Success(_) => dependentClassFilesLinks.+=(newLink)
              case Failure(exception) =>
                inputs.logger.error(
                  s"Failed to create link for invalidated file $foundClassFile: ${exception.getMessage()}"
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

    allInvalidatedExtraCompileProducts.++=(invalidatedExtraCompileProducts)
  }

  def generated(generatedClassFiles: Array[File]): Unit = {
    memoizedInvalidatedClassFiles = null
    generatedClassFiles.foreach { generatedClassFile =>
      val newClassFile = generatedClassFile.getAbsolutePath
      val relativeClassFilePath = newClassFile.replace(newClassesDirPath, "")
      allGeneratedRelativeClassFilePaths.put(relativeClassFilePath, generatedClassFile)
      val rebasedClassFile =
        new File(newClassFile.replace(newClassesDirPath, readOnlyClassesDirPath))
      // Delete generated class file + rebased class file because
      // invalidations can happen in both paths, no-op if missing
      allInvalidatedClassFilesForProject.-=(generatedClassFile)
      allInvalidatedClassFilesForProject.-=(rebasedClassFile)
      supportedCompileProducts.foreach { supportedProductSuffix =>
        val productName = rebasedClassFile
          .getName()
          .stripSuffix(".class") + supportedProductSuffix
        val productAssociatedToClassFile =
          new File(rebasedClassFile.getParentFile, productName)
        if (productAssociatedToClassFile.exists())
          allInvalidatedExtraCompileProducts.-=(productAssociatedToClassFile)
      }
    }
  }

  def complete(success: Boolean): Unit = {
    if (success) {
      dependentClassFilesLinks.foreach { unnecessaryClassFileLink =>
        Files.deleteIfExists(unnecessaryClassFileLink)
      }

      // Schedule copying compilation products to visible classes directory
      backgroundTasksWhenNewSuccessfulAnalysis.+=(
        (
            clientExternalClassesDir: AbsolutePath,
            clientReporter: Reporter,
            clientTracer: BraveTracer
        ) => {
          clientTracer.traceTaskVerbose("copy new products to external classes dir") { _ =>
            val config = ParallelOps.CopyConfiguration(5, CopyMode.ReplaceExisting, Set.empty)
            ParallelOps
              .copyDirectories(config)(
                newClassesDir,
                clientExternalClassesDir.underlying,
                inputs.ioScheduler,
                inputs.logger,
                enableCancellation = false
              )
              .map { walked =>
                readOnlyCopyBlacklist.++=(walked.target)
                ()
              }
          }
        }
      )
    } else {
      // Delete all compilation products generated in the new classes directory
      val deleteNewDir = Task { BloopPaths.delete(AbsolutePath(newClassesDir)); () }.memoize
      backgroundTasksForFailedCompilation.+=(
        (
            clientExternalClassesDir: AbsolutePath,
            clientReporter: Reporter,
            clientTracer: BraveTracer
        ) => {
          clientTracer.traceTask("delete class files after")(_ => deleteNewDir)
        }
      )

      backgroundTasksForFailedCompilation.+=(
        (
            clientExternalClassesDir: AbsolutePath,
            clientReporter: Reporter,
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
              clientTracer.traceTask("populate empty classes dir") {
                _ =>
                  // Prepopulate external classes dir even though compilation failed
                  val config = ParallelOps.CopyConfiguration(1, CopyMode.NoReplace, Set.empty)
                  ParallelOps
                    .copyDirectories(config)(
                      Paths.get(readOnlyClassesDirPath),
                      clientExternalClassesDir.underlying,
                      inputs.ioScheduler,
                      inputs.logger,
                      enableCancellation = false
                    )
                    .map(_ => ())
              }
          }
      )
    }
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
