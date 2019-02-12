package bloop

import bloop.io.AbsolutePath

import java.io.File
import java.nio.file.Path

import monix.reactive.{Observer, Observable, MulticastStrategy}
import monix.execution.Scheduler

case class CompileManagedOutput private (
    visibleOutPath: AbsolutePath,
    managedMutableOutPath: AbsolutePath,
    managedWriteOutPath: AbsolutePath,
    backupOutPath: AbsolutePath,
    waitOnEndOfClassesDirCopy: () => Unit
)

object CompileManagedOutput {
  def apply(
      visibleOutPath: AbsolutePath,
      managedMutableOutPath: AbsolutePath,
      waitOnEndOfClassesDirCopy: () => Unit
  ): CompileManagedOutput = {
    import java.util.UUID
    val uniquePathName = UUID.randomUUID().toString
    val backupPathName = uniquePathName + ".bak"
    val parentDir = managedMutableOutPath.getParent
    val backupOutPath = parentDir.resolve(backupPathName).createDirectories
    val managedWriteOutPath = parentDir.resolve(uniquePathName).createDirectories
    CompileManagedOutput(
      visibleOutPath,
      managedMutableOutPath,
      managedWriteOutPath,
      backupOutPath,
      waitOnEndOfClassesDirCopy
    )
  }
}
