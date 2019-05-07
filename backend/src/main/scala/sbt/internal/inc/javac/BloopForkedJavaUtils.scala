package sbt.internal.inc.javac

import java.io.File
import xsbti.Logger
import xsbti.Reporter

object BloopForkedJavaUtils {
  def launch(
      javaHome: Option[File],
      program: String,
      sources: Seq[File],
      options: Seq[String],
      log: Logger,
      reporter: Reporter
  ): Boolean = ForkedJava.launch(javaHome, "javac", sources, options, log, reporter)
}
