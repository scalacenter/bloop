package bloop.scalajs

import scala.collection.JavaConverters._
import java.nio.file.{Files, Path}

import org.scalajs.core.tools.io.{AtomicWritableFileVirtualJSFile, FileScalaJSIRContainer, FileVirtualBinaryFile, FileVirtualScalaJSIRFile, IRFileCache, VirtualJarFile}
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.{Level, Logger => JsLogger}
import bloop.Project
import bloop.config.Config.{JsConfig, LinkerMode}
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
    Files.walk(path).iterator().asScala.filter(isIrFile).toList

  def link(
      config: JsConfig,
      project: Project,
      mainClass: String,
      logger: BloopLogger
  ): Path = {
    val classpath = project.classpath.map(_.underlying)
    val classpathIrFiles = classpath
      .filter(Files.isDirectory(_))
      .flatMap(findIrFiles)
      .map(f => new FileVirtualScalaJSIRFile(f.toFile))

    val outputPath = project.out.underlying
    val target = project.out.resolve("out.js")

    val enableOptimizer = config.mode match {
      case LinkerMode.Debug => false
      case LinkerMode.Release => true
    }

    val cache = new IRFileCache().newCache
    val irContainers = FileScalaJSIRContainer.fromClasspath(classpath.map(_.toFile))
    val libraryIRs = cache.cached(irContainers)

    val initializer = ModuleInitializer.mainMethodWithArgs(mainClass, "main")
    val jsConfig = StandardLinker.Config().withOptimizer(enableOptimizer)
    StandardLinker(jsConfig).link(
      irFiles = classpathIrFiles ++ libraryIRs,
      moduleInitializers = Seq(initializer),
      output = AtomicWritableFileVirtualJSFile(target.toFile),
      logger = new Logger(logger)
    )

    target.underlying
  }
}
