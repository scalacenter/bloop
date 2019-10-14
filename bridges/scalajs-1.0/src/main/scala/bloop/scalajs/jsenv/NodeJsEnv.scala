package bloop.scalajs.jsenv

import java.nio.file.Path

import bloop.logging.{DebugFilter, Logger}
import bloop.scalajs.jsenv
import com.zaxxer.nuprocess.NuProcessBuilder
import monix.execution.atomic.AtomicBoolean
import org.scalajs.jsenv.nodejs.NodeJSEnv.SourceMap
import org.scalajs.io.{MemVirtualBinaryFile, VirtualBinaryFile}
import org.scalajs.jsenv.nodejs.{BloopComRun, Support}
import org.scalajs.jsenv.{
  ExternalJSRun,
  Input,
  JSComRun,
  JSEnv,
  JSRun,
  RunConfig,
  UnsupportedInputException
}

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}

// Copy pasted verbatim from Scala.JS environments
final class NodeJSConfig private (
    val executable: String,
    val args: List[String],
    val env: Map[String, String],
    val cwd: Option[Path],
    val sourceMap: SourceMap
) {
  private def this() = {
    this(
      executable = "node",
      args = Nil,
      env = Map.empty,
      cwd = None,
      sourceMap = SourceMap.EnableIfAvailable
    )
  }

  def withExecutable(executable: String): NodeJSConfig =
    copy(executable = executable)

  def withArgs(args: List[String]): NodeJSConfig =
    copy(args = args)

  def withEnv(env: Map[String, String]): NodeJSConfig =
    copy(env = env)

  def withCwd(cwd: Option[Path]): NodeJSConfig =
    copy(cwd = cwd)

  def withSourceMap(sourceMap: SourceMap): NodeJSConfig =
    copy(sourceMap = sourceMap)

  /** Forces enabling (true) or disabling (false) source maps. */
  def withSourceMap(sourceMap: Boolean): NodeJSConfig =
    withSourceMap(if (sourceMap) SourceMap.Enable else SourceMap.Disable)

  private def copy(
      executable: String = executable,
      args: List[String] = args,
      env: Map[String, String] = env,
      cwd: Option[Path] = cwd,
      sourceMap: SourceMap = sourceMap
  ): NodeJSConfig = {
    new NodeJSConfig(executable, args, env, cwd, sourceMap)
  }
}

object NodeJSConfig {

  /**
   * Returns a default configuration for a [[jsenv.NodeJSEnv]].
   *
   *  The defaults are:
   *
   *  - `executable`: `"node"`
   *  - `args`: `Nil`
   *  - `env`: `Map.empty`
   *  - `cwd`: `None`
   *  - `sourceMap`: [[org.scalajs.jsenv.nodejs.NodeJSEnv.SourceMap.EnableIfAvailable]]
   */
  def apply(): NodeJSConfig = new NodeJSConfig()
}

/**
 * See comments in [[bloop.scalajs.JsBridge]].
 *
 * Adapted from `scala-js/nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/NodeJSEnv.scala`.
 */
final class NodeJSEnv(logger: Logger, config: NodeJSConfig) extends JSEnv {
  override val name: String = "Node.js"
  private val env: Map[String, String] = Map("NODE_MODULE_CONTEXTS" -> "0") ++ config.env

  def start(input: Input, runConfig: RunConfig): JSRun = {
    NodeJSEnv.validator.validate(runConfig)
    NodeJSEnv.internalStart(logger, config, env)(initFiles ++ inputFiles(input), runConfig)
  }

  def startWithCom(input: Input, runConfig: RunConfig, onMessage: String => Unit): JSComRun = {
    NodeJSEnv.validator.validate(runConfig)
    BloopComRun.start(runConfig, onMessage) { comLoader =>
      val files = initFiles ::: (comLoader :: inputFiles(input))
      NodeJSEnv.internalStart(logger, config, env)(files, runConfig)
    }
  }

  private def initFiles: List[VirtualBinaryFile] = {
    val base = List(NodeJSEnv.runtimeEnv, Support.fixPercentConsole)
    config.sourceMap match {
      case SourceMap.Disable => base
      case SourceMap.EnableIfAvailable => NodeJSEnv.installSourceMapIfAvailable :: base
      case SourceMap.Enable => NodeJSEnv.installSourceMap :: base
    }
  }

  private def inputFiles(input: Input) = input match {
    case Input.ScriptsToLoad(scripts) => scripts
    case _ => throw new UnsupportedInputException(input)
  }
}

object NodeJSEnv {
  implicit val debugFilter: DebugFilter = DebugFilter.Test
  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  private lazy val installSourceMapIfAvailable: MemVirtualBinaryFile = {
    MemVirtualBinaryFile.fromStringUTF8(
      "sourceMapSupport.js",
      """
        |try {
        |  require('source-map-support').install();
        |} catch (e) {
        |};
      """.stripMargin
    )
  }

  private lazy val installSourceMap: MemVirtualBinaryFile = {
    MemVirtualBinaryFile.fromStringUTF8(
      "sourceMapSupport.js",
      "require('source-map-support').install();"
    )
  }

  private[jsenv] lazy val runtimeEnv: MemVirtualBinaryFile = {
    MemVirtualBinaryFile.fromStringUTF8(
      "scalaJSEnvInfo.js",
      """
        |__ScalaJSEnv = {
        |  exitFunction: function(status) { process.exit(status); }
        |};
      """.stripMargin
    )
  }

  def internalStart(logger: Logger, config: NodeJSConfig, env: Map[String, String])(
      files: List[VirtualBinaryFile],
      runConfig: RunConfig
  ): JSRun = {
    val command = config.executable :: config.args
    logger.debug(s"Starting process ${command.mkString(" ")}...")
    logger.debug(s"Current working directory: ${config.cwd}")
    logger.debug(s"Current environment: ${config.env}")

    val executionPromise = Promise[Unit]()
    val builder = new NuProcessBuilder(command.asJava, env.asJava)
    val handler = new NodeJsHandler(logger, executionPromise, files)
    builder.setProcessListener(handler)
    config.cwd.foreach(builder.setCwd)

    val processEnvironment = builder.environment()
    processEnvironment.clear()
    processEnvironment.putAll(config.env.asJava)

    val process = builder.start()
    process.wantWrite()

    new JSRun {
      private val isClosed = AtomicBoolean(false)
      override def future: Future[Unit] = executionPromise.future
      override def close(): Unit = {
        // Make sure we only destroy the process once, the test adapter can call this several times!
        if (!isClosed.getAndSet(true)) {
          logger.debug(s"Destroying process...")
          process.destroy(false)
          process.waitFor(400, _root_.java.util.concurrent.TimeUnit.MILLISECONDS)
          process.destroy(true)
          // Make sure that no matter what happens the `onExit` callback is invoked
          handler.cancel()
        }
        ()
      }
    }
  }
}
