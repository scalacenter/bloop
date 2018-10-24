package bloop.scalajs

import scala.collection.JavaConverters._
import java.nio.file.{Files, Path}

import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io.{AtomicWritableFileVirtualJSFile, FileVirtualBinaryFile, FileVirtualScalaJSIRFile, VirtualJarFile}
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.{Level, Logger => JsLogger}
import bloop.config.Config.{JsConfig, LinkerMode, ModuleKindJS}
import bloop.data.Project
import bloop.logging.{LogContext, Logger => BloopLogger}
import org.scalajs.core.tools.linker.backend.ModuleKind
import org.scalajs.core.tools.sem.Semantics

object JsBridge {

  private class Logger(logger: BloopLogger) extends JsLogger {
    override def log(level: Level, message: => String): Unit =
      level match {
        case Level.Error => logger.error(message)
        case Level.Warn => logger.warn(message)
        case Level.Info => logger.info(message)
        case Level.Debug => logger.debug(message)(LogContext.All)
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

  private def toIrJar(jar: Path) =
    IRContainer.Jar(new FileVirtualBinaryFile(jar.toFile) with VirtualJarFile)

  def link(
      config: JsConfig,
      project: Project,
      mainClass: String,
      target: Path,
      logger: BloopLogger
  ): Unit = {
    val classpath = project.classpath.map(_.underlying)
    val classpathIrFiles = classpath
      .filter(Files.isDirectory(_))
      .flatMap(findIrFiles)
      .map(f => new FileVirtualScalaJSIRFile(f.toFile))

    val semantics = config.mode match {
      case LinkerMode.Debug => Semantics.Defaults
      case LinkerMode.Release => Semantics.Defaults.optimized
    }

    val moduleKind = config.kind match {
      case ModuleKindJS.NoModule => ModuleKind.NoModule
      case ModuleKindJS.CommonJSModule => ModuleKind.CommonJSModule
    }

    val enableOptimizer = config.mode == LinkerMode.Release
    val jarFiles = classpath.filter(isJarFile).map(toIrJar)
    val scalajsIRFiles = jarFiles.flatMap(_.jar.sjsirFiles)
    val initializer = ModuleInitializer.mainMethodWithArgs(mainClass, "main")
    val jsConfig = StandardLinker
      .Config()
      .withOptimizer(enableOptimizer)
      .withClosureCompilerIfAvailable(enableOptimizer)
      .withSemantics(semantics)
      .withModuleKind(moduleKind)
      .withSourceMap(config.emitSourceMaps)

    StandardLinker(jsConfig).link(
      irFiles = classpathIrFiles ++ scalajsIRFiles,
      moduleInitializers = Seq(initializer),
      output = AtomicWritableFileVirtualJSFile(target.toFile),
      logger = new Logger(logger)
    )
  }
}
