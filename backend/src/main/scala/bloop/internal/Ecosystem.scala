package bloop.internal

import java.io.File
import java.util.Optional

import xsbti.compile.{
  ClassFileManager,
  ClassFileManagerUtil,
  DefaultExternalHooks,
  IncOptions,
  WrappedClassFileManager
}

object Ecosystem {
  def supportDotty(incOptions: IncOptions): IncOptions = {
    val tastyFileManager = new ClassFileManager {
      private[this] val inherited = ClassFileManagerUtil.getDefaultClassFileManager(incOptions)

      def delete(classes: Array[File]): Unit = {
        val tastySuffixes = List(".tasty", ".hasTasty")
        inherited.delete(classes flatMap { classFile =>
          if (classFile.getPath endsWith ".class") {
            val prefix = classFile.getAbsolutePath.stripSuffix(".class")
            tastySuffixes.map(suffix => new File(prefix + suffix)).filter(_.exists)
          } else Nil
        })
      }

      def invalidatedClassFiles(): Array[File] = inherited.invalidatedClassFiles
      def generated(classes: Array[File]): Unit = {}
      def complete(success: Boolean): Unit = {}
    }
    val inheritedHooks = incOptions.externalHooks
    val externalClassFileManager: Optional[ClassFileManager] = Option(
      inheritedHooks.getExternalClassFileManager.orElse(null)
    ) match {
      case Some(prevManager) =>
        Optional.of(WrappedClassFileManager.of(prevManager, Optional.of(tastyFileManager)))
      case None =>
        Optional.of(tastyFileManager)
    }

    val newExternalHooks =
      new DefaultExternalHooks(inheritedHooks.getExternalLookup, externalClassFileManager)
    incOptions.withExternalHooks(newExternalHooks)
  }
}
