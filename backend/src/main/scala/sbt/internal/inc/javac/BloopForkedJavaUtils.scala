package sbt.internal.inc.javac

import java.io.File
import xsbti.Logger
import xsbti.Reporter

object BloopForkedJavaUtils {
  def launch(
      javac: Option[File],
      binaryName: String,
      sources: Seq[File],
      options: Seq[String],
      log: Logger,
      reporter: Reporter
  ): Boolean = {
    def normalizeSlash(s: String) = s.replace(File.separatorChar, '/')

    val (jArgs, nonJArgs) = options.partition(_.startsWith("-J"))
    val allArguments = nonJArgs ++ sources.map(_.getAbsolutePath)

    val exe = javac match {
      case None => binaryName
      case Some(javacBinary) => javacBinary.getAbsolutePath()
    }

    ForkedJava.withArgumentFile(allArguments) { argsFile =>
      val forkArgs = jArgs :+ s"@${normalizeSlash(argsFile.getAbsolutePath)}"
      val cwd = new File(new File(".").getAbsolutePath).getCanonicalFile
      val javacLogger = new JavacLogger(log, reporter, cwd)
      var exitCode = -1
      try {
        exitCode = scala.sys.process.Process(exe +: forkArgs, cwd) ! javacLogger
      } finally {
        javacLogger.flush(binaryName, exitCode)
      }
      // We return true or false, depending on success.
      exitCode == 0
    }
  }
}
