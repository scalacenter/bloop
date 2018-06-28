package bloop.scalajs

import scala.collection.JavaConverters._
import java.nio.file.{Files, Path}

import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io.{
  AtomicWritableFileVirtualJSFile,
  FileVirtualBinaryFile,
  FileVirtualScalaJSIRFile,
  VirtualJSFile,
  VirtualJarFile,
  MemVirtualJSFile,
  FileVirtualJSFile
}
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.{Level, Logger => JsLogger}
import bloop.config.Config.{JsConfig, LinkerMode, ModuleKindJS}
import bloop.data.Project
import bloop.logging.{Logger => BloopLogger}
import org.scalajs.core.tools.jsdep.ResolvedJSDependency
import org.scalajs.core.tools.linker.backend.ModuleKind
import org.scalajs.core.tools.sem.Semantics
import org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
import org.scalajs.testadapter.TestAdapter

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

  private def toIrJar(jar: Path) =
    IRContainer.Jar(new FileVirtualBinaryFile(jar.toFile) with VirtualJarFile)

  def link(
      config: JsConfig,
      project: Project,
      mainClass: Option[String],
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
    val initializers =
      mainClass.toList.map(cls => ModuleInitializer.mainMethodWithArgs(cls, "main"))
    val jsConfig = StandardLinker
      .Config()
      .withOptimizer(enableOptimizer)
      .withClosureCompilerIfAvailable(enableOptimizer)
      .withSemantics(semantics)
      .withModuleKind(moduleKind)
      .withSourceMap(config.emitSourceMaps)

    StandardLinker(jsConfig).link(
      irFiles = classpathIrFiles ++ scalajsIRFiles,
      moduleInitializers = initializers,
      output = AtomicWritableFileVirtualJSFile(target.toFile),
      logger = new Logger(logger)
    )
  }

  /** @return (list of frameworks, function to close test adapter) */
  def testFrameworks(
      frameworkNames: Array[Array[String]],
      jsPath: Path,
      projectPath: Path,
      logger: BloopLogger): (Array[sbt.testing.Framework], () => Unit) = {
    // TODO It would be cleaner if the CWD of the Node process could be set
    val quotedPath = projectPath.toString.replaceAll("'", "\\'")

    class MyEnv(config: JSDOMNodeJSEnv.Config) extends JSDOMNodeJSEnv(config) {
      protected override def customInitFiles(): Seq[VirtualJSFile] =
        Seq(
          new MemVirtualJSFile("changePath.js")
            .withContent(s"require('process').chdir('$quotedPath');"))
    }

    val nodeModules = projectPath.resolve("node_modules").toString
    logger.debug("Node.js module path: " + nodeModules)

    val env = new MyEnv(
      JSDOMNodeJSEnv.Config().withEnv(Map("NODE_PATH" -> nodeModules))
    ).loadLibs(Seq(ResolvedJSDependency.minimal(new FileVirtualJSFile(jsPath.toFile))))

    val config = TestAdapter.Config().withLogger(new Logger(logger))
    val adapter = new TestAdapter(env, config)
    val result = adapter
      .loadFrameworks(frameworkNames.map(_.toList).toList)
      .flatMap(_.toList)
      .toArray

    (result, () => adapter.close())
  }
}
