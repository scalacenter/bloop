package bloop.scalajs

import org.scalajs.jsenv._
import org.scalajs.io._
import org.scalajs.io.JSUtils.escapeJS
import java.io._
import java.nio.ByteBuffer
import java.nio.file.Path

import bloop.logging.Logger

import scala.collection.JavaConverters._
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import org.scalajs.jsenv.nodejs.{ComRun, Support}

import scala.concurrent.{Future, Promise}

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

            logger.debug(s"Closed stream")
          }
      }

      output.flip()

      logger.debug(s"Writing ${output.limit()} bytes...")
    }

    if (currentFileIndex == files.length) {
      logger.debug(s"Closing stdin stream...")
      process.get.closeStdin(false)
    }

    currentFileIndex < files.length
  }

  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit =
    if (!closed) {
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)
      logger.error("[stderr] " + new String(bytes, "UTF-8").trim)
    }

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit =
    if (!closed) {
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)
      logger.debug("[stdout] " + new String(bytes, "UTF-8").trim)
    }

  override def onExit(statusCode: Int): Unit = {
    logger.debug(s"Process exited with status code $statusCode")

    currentStream.foreach(_.close())
    currentStream = None

    exit.success(())
  }
}

/**
 * Adapted from
 * scala-js/nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/NodeJSEnv.scala
 */
final class NodeJSEnv(logger: Logger, config: NodeJSEnv.Config) extends JSEnv {
  import NodeJSEnv._

  override val name: String = "Node.js"

  def start(input: Input, runConfig: RunConfig): JSRun = {
    NodeJSEnv.validator.validate(runConfig)
    internalStart(initFiles ++ inputFiles(input), runConfig)
  }

  def startWithCom(input: Input, runConfig: RunConfig, onMessage: String => Unit): JSComRun = {
    NodeJSEnv.validator.validate(runConfig)
    ComRun.start(runConfig, onMessage) { comLoader =>
      val files = initFiles ::: (comLoader :: inputFiles(input))
      internalStart(files, runConfig)
    }
  }

  private def internalStart(files: List[VirtualBinaryFile], runConfig: RunConfig): JSRun = {
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

  private def initFiles: List[VirtualBinaryFile] = {
    val base = List(NodeJSEnv.runtimeEnv, Support.fixPercentConsole)

    config.sourceMap match {
      case SourceMap.Disable => base
      case SourceMap.EnableIfAvailable => installSourceMapIfAvailable :: base
      case SourceMap.Enable => installSourceMap :: base
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

  private lazy val runtimeEnv = {
    MemVirtualBinaryFile.fromStringUTF8(
      "scalaJSEnvInfo.js",
      """
        |__ScalaJSEnv = {
        |  exitFunction: function(status) { process.exit(status); }
        |};
      """.stripMargin
    )
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

  final class Config private (
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

    def withExecutable(executable: String): Config =
      copy(executable = executable)

    def withArgs(args: List[String]): Config =
      copy(args = args)

    def withEnv(env: Map[String, String]): Config =
      copy(env = env)

    def withCwd(cwd: Option[Path]): Config =
      copy(cwd = cwd)

    def withSourceMap(sourceMap: SourceMap): Config =
      copy(sourceMap = sourceMap)

    /** Forces enabling (true) or disabling (false) source maps.
     *
     *  `sourceMap = true` maps to [[SourceMap.Enable]]. `sourceMap = false`
     *  maps to [[SourceMap.Disable]]. [[SourceMap.EnableIfAvailable]] is never
     *  used by this method.
     */
    def withSourceMap(sourceMap: Boolean): Config =
      withSourceMap(if (sourceMap) SourceMap.Enable else SourceMap.Disable)

    private def copy(
        executable: String = executable,
        args: List[String] = args,
        env: Map[String, String] = env,
        cwd: Option[Path] = cwd,
        sourceMap: SourceMap = sourceMap
    ): Config = {
      new Config(executable, args, env, cwd, sourceMap)
    }
  }

  object Config {

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
    def apply(): Config = new Config()
  }
}
