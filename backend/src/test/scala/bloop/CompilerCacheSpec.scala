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
import scala.concurrent.ExecutionContext

@Category(Array(classOf[FastTests]))
class CompilerCacheSpec {

  @Test
  // Checks that https://github.com/scalacenter/bloop/issues/956 is fixed
  def testTicket956(): Unit = {
    val tempDir = AbsolutePath(Files.createTempDirectory("compiler-cache-spec"))
    try {
      val compiler = javax.tools.ToolProvider.getSystemJavaCompiler
      if (compiler == null) {
        System.out.println("Ignore test because system Java compiler is not available")
      } else {
        val listener = new DiagnosticListener[JavaFileObject] {
          override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = ()
        }

        val logger = new bloop.logging.RecordingLogger()
        val javacFileManager = compiler.getStandardFileManager(
          listener,
          Locale.getDefault,
          Charset.defaultCharset
        )

        val classPath = StandardLocation.CLASS_OUTPUT
        val fo1 = javacFileManager.getJavaFileForOutput(
          classPath,
          "bloop.CompilerCacheSpec",
          JavaFileObject.Kind.CLASS,
          null
        )

        val fo2 = javacFileManager.getJavaFileForOutput(
          classPath,
          "bloop.CompilerCacheSpec",
          JavaFileObject.Kind.CLASS,
          null
        )

        val fo3 = javacFileManager.getJavaFileForOutput(
          classPath,
          "bloop.CompilerCache",
          JavaFileObject.Kind.CLASS,
          null
        )

        val classFileManager = new ClassFileManager {
          override def delete(classes: Array[File]): Unit = ()
          override def invalidatedClassFiles(): Array[File] = Array.empty
          override def generated(classes: Array[File]): Unit = ()
          override def complete(success: Boolean): Unit = ()
        }

        val wr1 = new WriteReportingJavaFileObject(fo1, classFileManager)
        val wr2 = new WriteReportingJavaFileObject(fo2, classFileManager)
        val wr3 = new WriteReportingJavaFileObject(fo3, classFileManager)

        val ec = ExecutionContext.global
        val compilerCache = new CompilerCache(null, tempDir, logger, List.empty, None, None, ec)
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
