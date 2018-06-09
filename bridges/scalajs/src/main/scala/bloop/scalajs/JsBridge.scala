package bloop.scalajs

import scala.collection.JavaConverters._

import java.nio.file.{Files, Path}

import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io.{
  AtomicWritableFileVirtualJSFile,
  FileVirtualBinaryFile,
  FileVirtualScalaJSIRFile,
  VirtualJarFile
}
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.{Level, Logger => JsLogger}

import bloop.Project
import bloop.cli.OptimizerConfig
import bloop.config.Config.JsConfig
import bloop.logging.{Logger => BloopLogger}

object JsBridge {
  private class Logger(logger: BloopLogger) extends JsLogger {
    override def log(level: Level, message: => String): Unit =
      level match {
        case Level.Error => logger.error(message)
        case Level.Warn => logger.warn(message)
        case Level.Info => logger.info(message)
        case Level.Debug => logger.debug(message)
      }
    override def success(message: => String): Unit = logger.info(message)
    override def trace(t: => Throwable): Unit = logger.trace(t)
  }

  @inline private def isIrFile(path: Path): Boolean =
    path.toString.endsWith(".sjsir")

  @inline private def isJarFile(path: Path): Boolean =
    path.toString.endsWith(".jar")

  private def findIrFiles(path: Path): List[Path] =
    Files
      .walk(path)
      .iterator()
      .asScala
      .filter(isIrFile)
      .toList

  def link(project: Project,
           mainClass: String,
           logger: BloopLogger,
           optimize: OptimizerConfig): Path = {
    val classpath = project.classpath.map(_.underlying)
    val classpathIrFiles = classpath
      .filter(Files.isDirectory(_))
      .flatMap(findIrFiles)
      .map(f => new FileVirtualScalaJSIRFile(f.toFile))

    val outputPath = project.out.underlying
    val target = project.out.resolve("out.js")

    val jarFiles =
      classpath
        .filter(isJarFile)
        .map(jar => IRContainer.Jar(new FileVirtualBinaryFile(jar.toFile) with VirtualJarFile))
    val jarIrFiles = jarFiles.flatMap(_.jar.sjsirFiles)

    val initializer =
      ModuleInitializer.mainMethodWithArgs(mainClass, "main")

    val enableOptimizer = optimize match {
      case OptimizerConfig.Debug => false
      case OptimizerConfig.Release => true
    }

    val config = StandardLinker.Config().withOptimizer(enableOptimizer)

    StandardLinker(config).link(
      irFiles = classpathIrFiles ++ jarIrFiles,
      moduleInitializers = Seq(initializer),
      output = AtomicWritableFileVirtualJSFile(target.toFile),
      logger = new Logger(logger)
    )
    target.underlying
  }

  private[scalajs] def defaultJsConfig(project: Project): JsConfig = {
    JsConfig(toolchainClasspath = Nil)
  }
}
