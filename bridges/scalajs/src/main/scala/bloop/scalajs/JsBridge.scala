package bloop.scalajs

import scala.collection.JavaConverters._
import java.nio.file.{Files, Path}
import java.io.File

import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io.{AtomicWritableFileVirtualJSFile, FileVirtualBinaryFile, FileVirtualScalaJSIRFile, VirtualJarFile}
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.{Level, Logger => JsLogger}
import bloop.Project
import bloop.logging.{Logger => BloopLogger}

object JsBridge {

  private class Logger(logger: BloopLogger) extends JsLogger {
    override def log(level: Level, message: => String): Unit =
      level match {
        case Level.Error => logger.error(message)
        case Level.Warn  => logger.warn(message)
        case Level.Info  => logger.info(message)
        case Level.Debug => logger.debug(message)
      }
    override def success(message: => String): Unit = logger.info(message)
    override def trace(t: => Throwable): Unit = logger.trace(t)
  }

  @inline private def isIrFile(file: File): Boolean =
    file.toString.endsWith(".sjsir")

  @inline private def isJarFile(file: File): Boolean =
    file.toString.endsWith(".jar")

  private def findIrFiles(path: Path): List[File] =
    Files.walk(path)
      .iterator()
      .asScala
      .map(_.toFile)
      .filter(isIrFile)
      .toList

  def link(project  : Project,
           mainClass: String,
           target   : File,
           optimise : java.lang.Boolean,
           logger   : BloopLogger): Unit = {
    val classPath = project.classpath.map(_.underlying).toList

    val classPathIrFiles = classPath
      .filter(_.toFile.isDirectory)
      .flatMap(findIrFiles)
      .map(new FileVirtualScalaJSIRFile(_))

    val jarFiles =
      classPath.map(_.toFile).filter(isJarFile).map(jar => IRContainer.Jar(
        new FileVirtualBinaryFile(jar) with VirtualJarFile))
    val jarIrFiles = jarFiles.flatMap(_.jar.sjsirFiles)

    val initializer =
      ModuleInitializer.mainMethodWithArgs(mainClass, "main")

    val config = StandardLinker.Config().withOptimizer(optimise)

    StandardLinker(config).link(
      irFiles            = classPathIrFiles ++ jarIrFiles,
      moduleInitializers = Seq(initializer),
      output             = AtomicWritableFileVirtualJSFile(target),
      logger             = new Logger(logger))
  }

}