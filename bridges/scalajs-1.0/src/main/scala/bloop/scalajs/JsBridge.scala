package bloop.scalajs

import java.nio.file.Path

import org.scalajs.io.{
  AtomicWritableFileVirtualJSFile,
  FileVirtualJSFile,
  MemVirtualJSFile,
  VirtualJSFile
}
import bloop.config.Config.{JsConfig, LinkerMode, ModuleKindJS}
import bloop.data.Project
import bloop.io.Paths
import bloop.logging.{DebugFilter, Logger => BloopLogger}
import org.scalajs.linker.irio.{FileScalaJSIRContainer, FileVirtualScalaJSIRFile, IRFileCache}
import org.scalajs.linker.{ModuleInitializer, ModuleKind, Semantics, StandardLinker}
import org.scalajs.logging.{Level, Logger => JsLogger}
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
import org.scalajs.testadapter.TestAdapter

object JsBridge {
  private class Logger(logger: BloopLogger) extends JsLogger {
    override def log(level: Level, message: => String): Unit =
      level match {
        case Level.Error => logger.error(message)
        case Level.Warn => logger.warn(message)
        case Level.Info => logger.info(message)
        case Level.Debug => logger.debug(message)(DebugFilter.Link)
      }
    override def success(message: => String): Unit = logger.info(message)
    override def trace(t: => Throwable): Unit = logger.trace(t)
  }

  private def isJarFile(path: Path): Boolean = path.toString.endsWith(".jar")

  def link(
      config: JsConfig,
      project: Project,
      mainClass: Option[String],
      target: Path,
      logger: BloopLogger
  ): Unit = {
    val enableOptimizer = config.mode == LinkerMode.Release

    val semantics = config.mode match {
      case LinkerMode.Debug => Semantics.Defaults
      case LinkerMode.Release => Semantics.Defaults.optimized
    }

    val moduleKind = config.kind match {
      case ModuleKindJS.NoModule => ModuleKind.NoModule
      case ModuleKindJS.CommonJSModule => ModuleKind.CommonJSModule
    }

    val classpath = project.classpath.map(_.underlying)

    val sourceIRs = {
      val classpathDirs = project.classpath.filter(_.isDirectory)
      val sjsirFiles = classpathDirs.flatMap(d => Paths.pathFilesUnder(d, "glob:**.sjsir")).distinct
      sjsirFiles.map(s => FileVirtualScalaJSIRFile(s.underlying.toFile))
    }

    val libraryIRs = {
      val cache = new IRFileCache().newCache
      val jarFiles = classpath.iterator.filter(isJarFile).map(_.toFile).toList
      val irContainers = FileScalaJSIRContainer.fromClasspath(jarFiles)
      cache.cached(irContainers)
    }

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
      irFiles = sourceIRs ++ libraryIRs,
      moduleInitializers = initializers,
      output = AtomicWritableFileVirtualJSFile(target.toFile),
      logger = new Logger(logger)
    )
  }

  private class CustomDomNodeEnv(config: JSDOMNodeJSEnv.Config, initFiles: Seq[MemVirtualJSFile])
      extends JSDOMNodeJSEnv(config) {
    protected override def customInitFiles(): Seq[VirtualJSFile] = initFiles
  }

  private class CustomNodeEnv(config: NodeJSEnv.Config, initFiles: Seq[MemVirtualJSFile])
      extends NodeJSEnv(config) {
    protected override def customInitFiles(): Seq[VirtualJSFile] = initFiles
  }

  /** @return (list of frameworks, function to close test adapter) */
  def testFrameworks(frameworkNames: List[List[String]],
                     jsPath: Path,
                     projectPath: Path,
                     logger: BloopLogger,
                     jsdom: java.lang.Boolean): (List[sbt.testing.Framework], () => Unit) = {
    // TODO It would be cleaner if the CWD of the Node process could be set
    val quotedPath = projectPath.toString.replaceAll("'", "\\'")
    val customScripts = Seq(
      new MemVirtualJSFile("changePath.js")
        .withContent(s"require('process').chdir('$quotedPath');"))

    val nodeModules = projectPath.resolve("node_modules").toString
    logger.debug("Node.js module path: " + nodeModules)
    val env = Map("NODE_PATH" -> nodeModules)

    val nodeEnv =
      if (!jsdom) new CustomNodeEnv(NodeJSEnv.Config().withEnv(env), customScripts)
      else new CustomDomNodeEnv(JSDOMNodeJSEnv.Config().withEnv(env), customScripts)

    val config = TestAdapter.Config().withLogger(new Logger(logger))
    val adapter = new TestAdapter(nodeEnv, Seq(new FileVirtualJSFile(jsPath.toFile)), config)
    val result = adapter.loadFrameworks(frameworkNames).flatMap(_.toList)

    (result, () => adapter.close())
  }
}
