package bloop.scalajs

import org.scalajs.jsenv._
import java.nio.ByteBuffer
import java.nio.file.Path

import java.io._
import java.nio.file.{Files, StandardCopyOption}
import java.net.URI

import org.scalajs.io._
import org.scalajs.io.JSUtils.escapeJS

import bloop.logging.Logger

import scala.collection.JavaConverters._
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import org.scalajs.jsenv.nodejs.{ComRun, Support}

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

class ProcessHandler(logger: Logger, exit: Promise[Unit], files: List[VirtualBinaryFile])
    extends NuAbstractProcessHandler {
  private val buffer = new Array[Byte](NuProcess.BUFFER_CAPACITY)
  private var currentFileIndex: Int = 0
  private var currentStream: Option[InputStream] = None

  private var process: Option[NuProcess] = None

  override def onStart(nuProcess: NuProcess): Unit = {
    logger.debug(s"Process started at PID ${nuProcess.getPID}")
    process = Some(nuProcess)
  }

  /** @return false if we have nothing else to write */
  override def onStdinReady(output: ByteBuffer): Boolean = {
    if (currentFileIndex < files.length) {
      files(currentFileIndex) match {
        case f: FileVirtualBinaryFile =>
          logger.debug(s"Sending file $f...")
          val path = f.file.getAbsolutePath
          val str = s"""require("${escapeJS(path)}");"""
          output.put(str.getBytes("UTF-8"))
          currentFileIndex += 1

        case f =>
          val in = currentStream.getOrElse {
            logger.debug(s"Sending file $f...")
            f.inputStream
          }
          currentStream = Some(in)

          if (in.available() != 0) {
            val read = in.read(buffer)
            output.put(buffer, 0, read)
          } else {
            output.put('\n'.toByte)

            in.close()
            currentStream = None
            currentFileIndex += 1
          }
      }

      output.flip()
    }

    if (currentFileIndex == files.length) {
      logger.debug(s"Closing stdin stream...")
      process.get.closeStdin(false)
    }

    currentFileIndex < files.length
  }

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit =
    if (!closed) {
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)

      // Do not use Bloop logger because it inserts new lines
      System.out.print(new String(bytes, "UTF-8"))
      System.out.flush()
    }

  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit =
    if (!closed) {
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)
      System.err.print(new String(bytes, "UTF-8"))
      System.err.flush()
    }

  override def onExit(statusCode: Int): Unit = {
    logger.debug(s"Process exited with status code $statusCode")

    currentStream.foreach(_.close())
    currentStream = None

    exit.success(())
  }
}

/** Requirements for source map support. */
sealed abstract class SourceMap

object SourceMap {

  /** Disable source maps. */
  case object Disable extends SourceMap

  /** Enable source maps if `source-map-support` is available. */
  case object EnableIfAvailable extends SourceMap

  /** Always enable source maps.
   *
   *  If `source-map-support` is not available, loading the .js code will
   *  fail.
   */
  case object Enable extends SourceMap
}

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

  /** Forces enabling (true) or disabling (false) source maps.
   *
   *  `sourceMap = true` maps to [[SourceMap.Enable]]. `sourceMap = false`
   *  maps to [[SourceMap.Disable]]. [[SourceMap.EnableIfAvailable]] is never
   *  used by this method.
   */
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

  /** Returns a default configuration for a [[NodeJSEnv]].
   *
   *  The defaults are:
   *
   *  - `executable`: `"node"`
   *  - `args`: `Nil`
   *  - `env`: `Map.empty`
   *  - `cwd`: `None`
   *  - `sourceMap`: [[SourceMap.EnableIfAvailable]]
   */
  def apply(): NodeJSConfig = new NodeJSConfig()
}

/**
 * Adapted from
 * scala-js/nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/NodeJSEnv.scala
 */
final class NodeJSEnv(logger: Logger, config: NodeJSConfig) extends JSEnv {
  override val name: String = "Node.js"

  def start(input: Input, runConfig: RunConfig): JSRun = {
    NodeJSEnv.validator.validate(runConfig)
    NodeJSEnv.internalStart(logger, config, env)(initFiles ++ inputFiles(input), runConfig)
  }

  def startWithCom(input: Input, runConfig: RunConfig, onMessage: String => Unit): JSComRun = {
    NodeJSEnv.validator.validate(runConfig)
    ComRun.start(runConfig, onMessage) { comLoader =>
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

  private def env: Map[String, String] =
    Map("NODE_MODULE_CONTEXTS" -> "0") ++ config.env
}

object NodeJSEnv {
  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  private lazy val installSourceMapIfAvailable = {
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

  private lazy val installSourceMap = {
    MemVirtualBinaryFile.fromStringUTF8(
      "sourceMapSupport.js",
      "require('source-map-support').install();")
  }

  lazy val runtimeEnv = {
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
      runConfig: RunConfig): JSRun = {
    val command = config.executable :: config.args

    logger.debug(s"Starting process ${command.mkString(" ")}...")
    logger.debug(s"Current working directory: ${config.cwd}")

    val promise = Promise[Unit]()

    val pb = new NuProcessBuilder(command.asJava, env.asJava)
    pb.setProcessListener(new ProcessHandler(logger, promise, files))
    config.cwd.foreach(pb.setCwd)
    val process = pb.start()
    process.wantWrite()

    new JSRun {
      override def future: Future[Unit] = promise.future
      override def close(): Unit = {
        logger.debug(s"Destroying process...")
        process.destroy(true)
        ()
      }
    }
  }
}

/**
 * Adapted from jsdom-nodejs-env/src/main/scala/org/scalajs/jsenv/jsdomnodejs/JSDOMNodeJSEnv.scala
 */
class JSDOMNodeJSEnv(logger: Logger, config: NodeJSConfig) extends JSEnv {
  val name: String = "Node.js with JSDOM"

  def start(input: Input, runConfig: RunConfig): JSRun = {
    JSDOMNodeJSEnv.validator.validate(runConfig)
    try {
      NodeJSEnv.internalStart(logger, config, env)(
        initFiles ++ codeWithJSDOMContext(input),
        runConfig)
    } catch {
      case NonFatal(t) =>
        JSRun.failed(t)
    }
  }

  def startWithCom(input: Input, runConfig: RunConfig, onMessage: String => Unit): JSComRun = {
    JSDOMNodeJSEnv.validator.validate(runConfig)
    ComRun.start(runConfig, onMessage) { comLoader =>
      val files = initFiles ::: (comLoader :: codeWithJSDOMContext(input))
      NodeJSEnv.internalStart(logger, config, env)(files, runConfig)
    }
  }

  private def initFiles: List[VirtualBinaryFile] =
    List(NodeJSEnv.runtimeEnv, Support.fixPercentConsole)

  private def env: Map[String, String] =
    Map("NODE_MODULE_CONTEXTS" -> "0") ++ config.env

  private def scriptFiles(input: Input): List[VirtualBinaryFile] = input match {
    case Input.ScriptsToLoad(scripts) => scripts
    case _ => throw new UnsupportedInputException(input)
  }

  private def codeWithJSDOMContext(input: Input): List[VirtualBinaryFile] = {
    val scriptsURIs = scriptFiles(input).map(JSDOMNodeJSEnv.materialize)
    val scriptsURIsAsJSStrings =
      scriptsURIs.map(uri => '"' + escapeJS(uri.toASCIIString) + '"')
    val jsDOMCode = {
      s"""
         |(function () {
         |  var jsdom;
         |  try {
         |    jsdom = require("jsdom/lib/old-api.js"); // jsdom >= 10.x
         |  } catch (e) {
         |    jsdom = require("jsdom"); // jsdom <= 9.x
         |  }
         |
         |  var virtualConsole = jsdom.createVirtualConsole()
         |    .sendTo(console, { omitJsdomErrors: true });
         |  virtualConsole.on("jsdomError", function (error) {
         |    /* This inelegant if + console.error is the only way I found
         |     * to make sure the stack trace of the original error is
         |     * printed out.
         |     */
         |    if (error.detail && error.detail.stack)
         |      console.error(error.detail.stack);
         |
         |    // Throw the error anew to make sure the whole execution fails
         |    throw error;
         |  });
         |
         |  /* Work around the fast that scalajsCom.init() should delay already
         |   * received messages to the next tick. Here we cannot tell whether
         |   * the receive callback is called for already received messages or
         |   * not, so we dealy *all* messages to the next tick.
         |   */
         |  var scalajsCom = global.scalajsCom;
         |  var scalajsComWrapper = scalajsCom === (void 0) ? scalajsCom : ({
         |    init: function(recvCB) {
         |      scalajsCom.init(function(msg) {
         |        process.nextTick(recvCB, msg);
         |      });
         |    },
         |    send: function(msg) {
         |      scalajsCom.send(msg);
         |    }
         |  });
         |
         |  jsdom.env({
         |    html: "",
         |    url: "http://localhost/",
         |    virtualConsole: virtualConsole,
         |    created: function (error, window) {
         |      if (error == null) {
         |        window["__ScalaJSEnv"] = __ScalaJSEnv;
         |        window["scalajsCom"] = scalajsComWrapper;
         |      } else {
         |        throw error;
         |      }
         |    },
         |    scripts: [${scriptsURIsAsJSStrings.mkString(", ")}]
         |  });
         |})();
         |""".stripMargin
    }
    List(MemVirtualBinaryFile.fromStringUTF8("codeWithJSDOMContext.js", jsDOMCode))
  }
}

object JSDOMNodeJSEnv {
  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  // tmpSuffixRE and tmpFile copied from HTMLRunnerBuilder.scala in Scala.js

  private val tmpSuffixRE = """[a-zA-Z0-9-_.]*$""".r

  private def tmpFile(path: String, in: InputStream): URI = {
    try {
      /* - createTempFile requires a prefix of at least 3 chars
       * - we use a safe part of the path as suffix so the extension stays (some
       *   browsers need that) and there is a clue which file it came from.
       */
      val suffix = tmpSuffixRE.findFirstIn(path).orNull

      val f = File.createTempFile("tmp-", suffix)
      f.deleteOnExit()
      Files.copy(in, f.toPath, StandardCopyOption.REPLACE_EXISTING)
      f.toURI
    } finally {
      in.close()
    }
  }

  private def materialize(file: VirtualBinaryFile): URI = {
    file match {
      case file: FileVirtualFile => file.file.toURI
      case f => tmpFile(f.path, f.inputStream)
    }
  }
}
