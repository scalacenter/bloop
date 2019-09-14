package bloop

import java.nio.charset.Charset
import java.util.Locale

import bloop.io.AbsolutePath
import javax.tools.{Diagnostic, DiagnosticListener, JavaFileObject, StandardLocation}
import org.junit.Assert._
import org.junit.Test
import org.junit.experimental.categories.Category
import sbt.internal.inc.javac.WriteReportingJavaFileObject
import sbt.io.syntax.File
import xsbti.compile.ClassFileManager
import java.nio.file.Files
import bloop.io.Paths

@Category(Array(classOf[FastTests]))
class CompilerCacheSpec {

  @Test
  def testInvalidatingFileManager(): Unit = {
    val tempDir = AbsolutePath(Files.createTempDirectory("compiler-cache-spec"))
    try {
      val compiler = javax.tools.ToolProvider.getSystemJavaCompiler
      if (compiler == null) {
        System.out.println("Ignore test because system Java compiler is not available")
      } else {

        val listener = new DiagnosticListener[JavaFileObject] {
          override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = {}
        }
        val logger = new bloop.logging.RecordingLogger()
        val javacFileManager =
          compiler.getStandardFileManager(listener, Locale.getDefault, Charset.defaultCharset)

        val classPath = StandardLocation.CLASS_PATH
        val fo1 = javacFileManager.getJavaFileForInput(
          classPath,
          "bloop.CompilerCacheSpec",
          JavaFileObject.Kind.CLASS
        )
        val fo2 = javacFileManager.getJavaFileForInput(
          classPath,
          "bloop.CompilerCacheSpec",
          JavaFileObject.Kind.CLASS
        )
        val fo3 = javacFileManager.getJavaFileForInput(
          classPath,
          "bloop.CompilerCache",
          JavaFileObject.Kind.CLASS
        )

        val classFileManager = new ClassFileManager {
          override def delete(classes: Array[File]): Unit = {}
          override def invalidatedClassFiles(): Array[File] = Array.empty
          override def generated(classes: Array[File]): Unit = {}
          override def complete(success: Boolean): Unit = {}
        }

        val wr1 = new WriteReportingJavaFileObject(fo1, classFileManager)
        val wr2 = new WriteReportingJavaFileObject(fo2, classFileManager)
        val wr3 = new WriteReportingJavaFileObject(fo3, classFileManager)

        val compilerCache = new CompilerCache(null, tempDir, logger, List.empty)
        val bloopCompiler = new compilerCache.BloopJavaCompiler(compiler)
        val invalidatingFileManager =
          new bloopCompiler.BloopInvalidatingFileManager(javacFileManager, classFileManager)

        assertTrue(invalidatingFileManager.isSameFile(fo1, fo2))
        assertFalse(invalidatingFileManager.isSameFile(fo1, fo3))
        assertTrue(invalidatingFileManager.isSameFile(wr1, wr2))
        assertTrue(invalidatingFileManager.isSameFile(wr1, fo2))
        assertTrue(invalidatingFileManager.isSameFile(fo1, wr2))
        assertFalse(invalidatingFileManager.isSameFile(wr1, wr3))
        assertFalse(invalidatingFileManager.isSameFile(wr1, fo3))
        assertFalse(invalidatingFileManager.isSameFile(fo1, wr3))
      }
    } finally {
      Paths.delete(tempDir)
    }
  }

}
