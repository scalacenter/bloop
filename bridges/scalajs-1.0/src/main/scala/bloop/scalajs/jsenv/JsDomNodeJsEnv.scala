package bloop.scalajs.jsenv

import java.io.{File, InputStream}
import java.net.URI
import java.nio.file.{Files, StandardCopyOption}

import bloop.logging.Logger
import org.scalajs.io.{FileVirtualFile, JSUtils, MemVirtualBinaryFile, VirtualBinaryFile}
import org.scalajs.jsenv.nodejs.{BloopComRun, ComRun, Support}
import org.scalajs.jsenv.{
  ExternalJSRun,
  Input,
  JSComRun,
  JSEnv,
  JSRun,
  RunConfig,
  UnsupportedInputException
}

import scala.util.control.NonFatal

/**
 * See comments in [[bloop.scalajs.JsBridge]].
 *
 * Adapted from `jsdom-nodejs-env/src/main/scala/org/scalajs/jsenv/jsdomnodejs/JSDOMNodeJSEnv.scala`.
 */
class JsDomNodeJsEnv(logger: Logger, config: NodeJSConfig) extends JSEnv {
  val name: String = "Node.js with JSDOM"

  def start(input: Input, runConfig: RunConfig): JSRun = {
    JsDomNodeJsEnv.validator.validate(runConfig)
    try {
      NodeJSEnv.internalStart(logger, config, env)(
        initFiles ++ codeWithJSDOMContext(input),
        runConfig
      )
    } catch {
      case NonFatal(t) =>
        JSRun.failed(t)
    }
  }

  def startWithCom(input: Input, runConfig: RunConfig, onMessage: String => Unit): JSComRun = {
    JsDomNodeJsEnv.validator.validate(runConfig)
    BloopComRun.start(runConfig, onMessage) { comLoader =>
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
    val scriptsURIs = scriptFiles(input).map(JsDomNodeJsEnv.materialize)
    val scriptsURIsAsJSStrings =
      scriptsURIs.map(uri => '"' + JSUtils.escapeJS(uri.toASCIIString) + '"')
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

object JsDomNodeJsEnv {
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
