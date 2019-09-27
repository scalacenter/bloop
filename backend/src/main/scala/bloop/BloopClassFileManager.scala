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

final class BloopClassFileManager(
    inputs: CompileInputs,
    outPaths: CompileOutPaths,
    allGeneratedRelativeClassFilePaths: mutable.HashMap[String, File],
    copiedPathsFromNewClassesDir: mutable.HashSet[Path],
    allInvalidatedClassFilesForProject: mutable.HashSet[File],
    allInvalidatedExtraCompileProducts: mutable.HashSet[File],
    backgroundTasksWhenNewSuccessfulAnalysis: mutable.ListBuffer[CompileBackgroundTasks.Sig],
    backgroundTasksForFailedCompilation: mutable.ListBuffer[CompileBackgroundTasks.Sig]
) extends ClassFileManager {
  private[this] val readOnlyClassesDir = outPaths.internalReadOnlyClassesDir.underlying
  private[this] val readOnlyClassesDirPath = readOnlyClassesDir.toString
  private[this] val newClassesDir = outPaths.internalNewClassesDir.underlying
  private[this] val newClassesDirPath = newClassesDir.toString

  // Supported compile products by the class file manager
  private[this] final val supportedCompileProducts = List(".sjsir", ".nir", ".tasty")

  /*
   * Initialize the class files from dependent projects that should not be
   * loaded. The value is equal to the invalidations in dependent projects
   * minus the freshly generated class files in dependent projects. This
   * processing is key to avoid "not found type" or not found symbols during
   * incremental compilation.
   */
  private[this] val dependentClassFilesThatShouldNotBeLoaded: Set[File] = {
    inputs.invalidatedClassFilesInDependentProjects --
      inputs.generatedClassFilePathsInDependentProjects.valuesIterator
  }

  /**
   * Returns the set of all invalidated class files.
   *
   * This method is called once per iteration by the Scala compiler but can be
   * called more than once by the Java compiler, hence its memoization.
   */
  def invalidatedClassFilesSet: mutable.HashSet[File] = {
    allInvalidatedClassFilesForProject
  }

  private[this] var memoizedInvalidatedClassFiles: Array[File] = _
  def invalidatedClassFiles(): Array[File] = {
    if (memoizedInvalidatedClassFiles == null) {
      memoizedInvalidatedClassFiles =
        (allInvalidatedClassFilesForProject ++ dependentClassFilesThatShouldNotBeLoaded).toArray
    }

    memoizedInvalidatedClassFiles
  }

  def delete(classes: Array[File]): Unit = {
    memoizedInvalidatedClassFiles = null

    import inputs.generatedClassFilePathsInDependentProjects
    val classesToInvalidate = classes.filter { classFile =>
      // Don't invalidate a class file if generated earlier by a dependency of
      // this project, which happens when users move sources around projects
      val relativeFilePath = classFile.getAbsolutePath.replace(readOnlyClassesDirPath, "")
      !generatedClassFilePathsInDependentProjects.contains(relativeFilePath)
    }

    // Add to the blacklist so that we never copy them
    allInvalidatedClassFilesForProject.++=(classesToInvalidate)
    val invalidatedExtraCompileProducts = classesToInvalidate.flatMap { classFile =>
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
      // Schedule copying compilation products to visible classes directory
      backgroundTasksWhenNewSuccessfulAnalysis.+=(
        (clientExternalClassesDir: AbsolutePath, clientTracer: BraveTracer) => {
          clientTracer.traceTask("copy new products to external classes dir") { _ =>
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
                copiedPathsFromNewClassesDir.++=(walked.target)
                ()
              }
          }
        }
      )
    } else {
      // Delete all compilation products generated in the new classes directory
      val deleteNewDir = Task { BloopPaths.delete(AbsolutePath(newClassesDir)); () }.memoize
      backgroundTasksForFailedCompilation.+=(
        (clientExternalClassesDir: AbsolutePath, clientTracer: BraveTracer) => {
          clientTracer.traceTask("delete class files after")(_ => deleteNewDir)
        }
      )

      backgroundTasksForFailedCompilation.+=(
        (clientExternalClassesDir: AbsolutePath, clientTracer: BraveTracer) => {
          clientTracer.traceTask("populate external classes dir as it's empty") { _ =>
            Task {
              if (!BloopPaths.isDirectoryEmpty(clientExternalClassesDir)) Task.unit
              else {
                if (BloopPaths.isDirectoryEmpty(outPaths.internalReadOnlyClassesDir)) {
                  Task.unit
                } else {
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
            }.flatten
          }
        }
      )
    }
  }
}
