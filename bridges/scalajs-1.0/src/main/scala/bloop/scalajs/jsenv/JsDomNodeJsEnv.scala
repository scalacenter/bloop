package bloop.scalajs.jsenv

import java.io.{File, InputStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

import bloop.logging.Logger
import com.google.common.jimfs.Jimfs
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

import scala.util.control.NonFatal

/**
 * See comments in [[bloop.scalajs.JsBridge]].
 *
 * Adapted from `jsdom-nodejs-env/src/main/scala/org/scalajs/jsenv/jsdomnodejs/JSDOMNodeJSEnv.scala`.
 */
class JsDomNodeJsEnv(logger: Logger, config: NodeJSConfig) extends JSEnv {
  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  val name: String = "Node.js with jsdom"

  def start(input: Seq[Input], runConfig: RunConfig): JSRun = {
    JsDomNodeJsEnv.validator.validate(runConfig)
    val scripts = validateInput(input)
    try {
      internalStart(codeWithJSDOMContext(scripts), runConfig)
    } catch {
      case NonFatal(t) =>
        JSRun.failed(t)
    }
  }

  def startWithCom(input: Seq[Input], runConfig: RunConfig, onMessage: String => Unit): JSComRun = {
    validator.validate(runConfig)
    val scripts = validateInput(input)
    BloopComRun.start(runConfig, onMessage) { comLoader =>
      internalStart(comLoader :: codeWithJSDOMContext(scripts), runConfig)
    }
  }

  private def validateInput(input: Seq[Input]): List[Path] = {
    input.map {
      case Input.Script(script) =>
        script

      case _ =>
        throw new UnsupportedInputException(input)
    }.toList
  }

  private def internalStart(files: List[Path], runConfig: RunConfig): JSRun =
    NodeJSEnv.internalStart(logger, config, env)(
      NodeJSEnv.write(files.map(Input.Script)),
      runConfig
    )

  private def env: Map[String, String] =
    Map("NODE_MODULE_CONTEXTS" -> "0") ++ config.env

  private def codeWithJSDOMContext(scripts: List[Path]): List[Path] = {
    val scriptsURIs = scripts.map(JsDomNodeJsEnv.materialize)
    val scriptsURIsAsJSStrings =
      scriptsURIs.map(uri => "\"" + escapeJS(uri.toASCIIString) + "\"")
    val scriptsURIsJSArray = scriptsURIsAsJSStrings.mkString("[", ", ", "]")
    val jsDOMCode = {
      s"""
         |
         |(function () {
         |  var jsdom = require("jsdom");
         |
         |  if (typeof jsdom.JSDOM === "function") {
         |    // jsdom >= 10.0.0
         |    var virtualConsole = new jsdom.VirtualConsole()
         |      .sendTo(console, { omitJSDOMErrors: true });
         |    virtualConsole.on("jsdomError", function (error) {
         |      try {
         |        // Display as much info about the error as possible
         |        if (error.detail && error.detail.stack) {
         |          console.error("" + error.detail);
         |          console.error(error.detail.stack);
         |        } else {
         |          console.error(error);
         |        }
         |      } finally {
         |        // Whatever happens, kill the process so that the run fails
         |        process.exit(1);
         |      }
         |    });
         |
         |    var dom = new jsdom.JSDOM("", {
         |      virtualConsole: virtualConsole,
         |      url: "http://localhost/",
         |
         |      /* Allow unrestricted <script> tags. This is exactly as
         |       * "dangerous" as the arbitrary execution of script files we
         |       * do in the non-jsdom Node.js env.
         |       */
         |      resources: "usable",
         |      runScripts: "dangerously"
         |    });
         |
         |    var window = dom.window;
         |    window["scalajsCom"] = global.scalajsCom;
         |
         |    var scriptsSrcs = $scriptsURIsJSArray;
         |    for (var i = 0; i < scriptsSrcs.length; i++) {
         |      var script = window.document.createElement("script");
         |      script.src = scriptsSrcs[i];
         |      window.document.body.appendChild(script);
         |    }
         |  } else {
         |    // jsdom v9.x
         |    var virtualConsole = jsdom.createVirtualConsole()
         |      .sendTo(console, { omitJsdomErrors: true });
         |    virtualConsole.on("jsdomError", function (error) {
         |      /* This inelegant if + console.error is the only way I found
         |       * to make sure the stack trace of the original error is
         |       * printed out.
         |       */
         |      if (error.detail && error.detail.stack)
         |        console.error(error.detail.stack);
         |
         |      // Throw the error anew to make sure the whole execution fails
         |      throw error;
         |    });
         |
         |    jsdom.env({
         |      html: "",
         |      virtualConsole: virtualConsole,
         |      url: "http://localhost/",
         |      created: function (error, window) {
         |        if (error == null) {
         |          window["scalajsCom"] = global.scalajsCom;
         |        } else {
         |          throw error;
         |        }
         |      },
         |      scripts: $scriptsURIsJSArray
         |    });
         |  }
         |})();
         |""".stripMargin
    }
    List(
      Files.write(
        Jimfs.newFileSystem().getPath("codeWithJSDOMContext.js"),
        jsDOMCode.getBytes(StandardCharsets.UTF_8)
      )
    )
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

  private def materialize(path: Path): URI = {
    try {
      path.toFile.toURI
    } catch {
      case _: UnsupportedOperationException =>
        tmpFile(path.toString, Files.newInputStream(path))
    }
  }
}
