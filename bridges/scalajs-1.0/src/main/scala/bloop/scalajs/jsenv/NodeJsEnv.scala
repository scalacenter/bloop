package bloop.scalajs.jsenv

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

import bloop.logging.{DebugFilter, Logger}
import bloop.scalajs.jsenv
import com.google.common.jimfs.Jimfs
import com.zaxxer.nuprocess.NuProcessBuilder
import monix.execution.atomic.AtomicBoolean
import org.scalajs.jsenv.nodejs.NodeJSEnv.SourceMap
import org.scalajs.jsenv.nodejs.BloopComRun
import org.scalajs.jsenv.{
  ExternalJSRun,
  Input,
  JSComRun,
  JSEnv,
  JSRun,
  RunConfig,
  UnsupportedInputException
}
import org.scalajs.jsenv.JSUtils.escapeJS

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}

// Copy pasted verbatim from Scala.js source code, added withCwd()
// Original file: scala-js/nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/NodeJSEnv.scala
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
    withSourceMap(if (sourceMap) SourceMap.EnableIfAvailable else SourceMap.Disable)

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
  private def env: Map[String, String] = Map("NODE_MODULE_CONTEXTS" -> "0") ++ config.env

  def start(input: Seq[Input], runConfig: RunConfig): JSRun = {
    NodeJSEnv.validator.validate(runConfig)
    validateInput(input)
    internalStart(initFiles ++ input, runConfig)
  }

  def startWithCom(input: Seq[Input], runConfig: RunConfig, onMessage: String => Unit): JSComRun = {
    NodeJSEnv.validator.validate(runConfig)
    validateInput(input)
    BloopComRun.start(runConfig, onMessage) { comLoader =>
      internalStart(initFiles ++ (Input.Script(comLoader) +: input), runConfig)
    }
  }

  private def validateInput(input: Seq[Input]): Unit = input.foreach {
    case _: Input.Script | _: Input.ESModule | _: Input.CommonJSModule =>
    // ok
    case _ =>
      throw new UnsupportedInputException(input)
  }

  private def internalStart(input: Seq[Input], runConfig: RunConfig): JSRun = {
    NodeJSEnv.internalStart(logger, config, env)(NodeJSEnv.write(input), runConfig)
  }

  import NodeJSEnv.installSourceMap
  import NodeJSEnv.installSourceMapIfAvailable

  private def initFiles: Seq[Input] = config.sourceMap match {
    case SourceMap.Disable => Nil
    case SourceMap.EnableIfAvailable => Input.Script(installSourceMapIfAvailable) :: Nil
    case SourceMap.Enable => Input.Script(installSourceMap) :: Nil
  }
}

object NodeJSEnv {
  private lazy val fs = Jimfs.newFileSystem()
  implicit val debugFilter: DebugFilter = DebugFilter.Test
  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  private lazy val installSourceMapIfAvailable = {
    Files.write(
      fs.getPath("optionalSourceMapSupport.js"),
      """
        |try {
        |  require('source-map-support').install();
        |} catch (e) {
        |};
        """.stripMargin.getBytes(StandardCharsets.UTF_8)
    )
  }

  private lazy val installSourceMap = {
    Files.write(
      fs.getPath("sourceMapSupport.js"),
      "require('source-map-support').install();".getBytes(StandardCharsets.UTF_8)
    )
  }

  private[jsenv] def write(input: Seq[Input])(out: ByteBuffer): Unit = {
    def runScript(path: Path): String = {
      try {
        val f = path.toFile
        val pathJS = "\"" + escapeJS(f.getAbsolutePath) + "\""
        s"""
          require('vm').runInThisContext(
            require('fs').readFileSync($pathJS, { encoding: "utf-8" }),
            { filename: $pathJS, displayErrors: true }
          )
        """
      } catch {
        case _: UnsupportedOperationException =>
          val code = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
          val codeJS = "\"" + escapeJS(code) + "\""
          val pathJS = "\"" + escapeJS(path.toString) + "\""
          s"""
            require('vm').runInThisContext(
              $codeJS,
              { filename: $pathJS, displayErrors: true }
            )
          """
      }
    }

    def requireCommonJSModule(module: Path): String =
      s"""require("${escapeJS(toFile(module).getAbsolutePath)}")"""

    def importESModule(module: Path): String =
      s"""import("${escapeJS(toFile(module).toURI.toASCIIString)}")"""

    def execInputExpr(input: Input): String = input match {
      case Input.Script(script) => runScript(script)
      case Input.CommonJSModule(module) => requireCommonJSModule(module)
      case Input.ESModule(module) => importESModule(module)
    }

    def println(str: String): Unit = {
      out.put((str + "\n").getBytes("UTF-8"))
      ()
    }

    try {
      if (!input.exists(_.isInstanceOf[Input.ESModule])) {
        /* If there is no ES module in the input, we can do everything
         * synchronously, and directly on the standard input.
         */
        for (item <- input)
          println(execInputExpr(item) + ";")
      } else {
        /* If there is at least one ES module, we must asynchronous chain things,
         * and we must use an actual file to feed code to Node.js (because
         * `import()` cannot be used from the standard input).
         */
        val importChain = input.foldLeft("Promise.resolve()") { (prev, item) =>
          s"$prev.\n  then(${execInputExpr(item)})"
        }
        val importerFileContent = {
          s"""
             |$importChain.catch(e => {
             |  console.error(e);
             |  process.exit(1);
             |});
          """.stripMargin
        }
        val f = createTmpFile("importer.js")
        Files.write(f.toPath, importerFileContent.getBytes(StandardCharsets.UTF_8))
        println(s"""require("${escapeJS(f.getAbsolutePath)}");""")
      }
    } finally {
      out.flip()
      ()
    }
  }

  private def toFile(path: Path): File = {
    try {
      path.toFile
    } catch {
      case _: UnsupportedOperationException =>
        val f = createTmpFile(path.toString)
        Files.copy(path, f.toPath, StandardCopyOption.REPLACE_EXISTING)
        f
    }
  }

  // tmpSuffixRE and createTmpFile copied from HTMLRunnerBuilder.scala

  private val tmpSuffixRE = """[a-zA-Z0-9-_.]*$""".r

  private def createTmpFile(path: String): File = {
    /* - createTempFile requires a prefix of at least 3 chars
     * - we use a safe part of the path as suffix so the extension stays (some
     *   browsers need that) and there is a clue which file it came from.
     */
    val suffix = tmpSuffixRE.findFirstIn(path).orNull

    val f = File.createTempFile("tmp-", suffix)
    f.deleteOnExit()
    f
  }

  def internalStart(logger: Logger, config: NodeJSConfig, env: Map[String, String])(
      write: ByteBuffer => Unit,
      runConfig: RunConfig
  ): JSRun = {
    val command = config.executable :: config.args
    logger.debug(s"Starting process ${command.mkString(" ")}...")
    logger.debug(s"Current working directory: ${config.cwd}")
    logger.debug(s"Current environment: ${config.env}")

    val executionPromise = Promise[Unit]()
    val builder = new NuProcessBuilder(command.asJava, env.asJava)
    val handler = new NodeJsHandler(logger, executionPromise, write)
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
