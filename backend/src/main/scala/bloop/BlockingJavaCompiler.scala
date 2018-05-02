package bloop

import java.io.File
import java.util.concurrent.CompletableFuture

import sbt.internal.inc.bloop.ZincInternals
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import xsbti.{Logger, Reporter}
import xsbti.compile.{IncToolOptions, JavaCompiler}

final class BlockingJavaCompiler(ready: CompletableFuture[Seq[File]], javaCompiler: JavaCompiler)
    extends JavaCompiler {
  override def run(
      files: Array[File],
      args: Array[String],
      opts: IncToolOptions,
      reporter: Reporter,
      logger: Logger
  ): Boolean = {
    def notifyExit = {
      logger.warn(() => "Skipping Java compilation because pipelined compilation didn't succeed.")
      false
    }

    def compileJava(classpath: Seq[File]): Boolean = {
      val compiler = javaCompiler match {
        case a: AnalyzingJavaCompiler =>
          // format: OFF
          ZincInternals.instantiateJavaCompiler(a.javac, classpath, a.scalaInstance, a.classpathOptions, a.classLookup, a.searchClasspath)
          // format: ON
        case _ =>
          logger.warn(() => "Unknown underlying Java compiler; classpath from pipelined compilation is ignored.")
          javaCompiler
      }

      compiler.run(files, args, opts, reporter, logger)
    }


    javaCompiler.run(files, args, opts, reporter, logger)
/*    try {
      val classpath = ready.get()
      if (classpath.isEmpty) notifyExit
      else compileJava(classpath)
    } catch { case t: Throwable => notifyExit }*/
  }
}
