package bloop.scalajs

import scala.collection.JavaConverters._

import java.nio.file.Files
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

  def link(project  : Project,
           mainClass: String,
           target   : File,
           optimise : java.lang.Boolean,
           logger   : BloopLogger): Unit = {
    val outputPath = project.out.underlying

    val sources = Files.walk(outputPath).iterator().asScala
      .filter(_.getFileName.toString.endsWith(".sjsir")).map(_.toFile).toList
    val sourceIrFiles = sources.map(new FileVirtualScalaJSIRFile(_))

    val classPath = project.classpath.map(_.underlying)
    val jarFiles =
      classPath.toList.map(_.toFile).filter(_.getName.endsWith(".jar"))
    val jars = jarFiles.map(jar =>
      IRContainer.Jar(new FileVirtualBinaryFile(jar) with VirtualJarFile))
    val jarIrFiles = jars.flatMap(_.jar.sjsirFiles)

    val irFiles = sourceIrFiles ++ jarIrFiles

    val initializer =
      ModuleInitializer.mainMethodWithArgs(mainClass, "main")

    val config = StandardLinker.Config().withOptimizer(optimise)

    StandardLinker(config).link(
      irFiles            = irFiles,
      moduleInitializers = Seq(initializer),
      output             = AtomicWritableFileVirtualJSFile(target),
      logger             = new Logger(logger))
  }

}