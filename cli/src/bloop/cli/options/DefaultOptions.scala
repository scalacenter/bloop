package bloop.cli.options

import caseapp._
import bloop.rifle.BloopRifleConfig
import bloop.cli.util.OsLibc
import coursier.cache.FileCache
import scala.util.control.NonFatal
import coursier.cache.ArchiveCache
import scala.concurrent.ExecutionContextExecutorService
import bloop.cli.util.CsLoggerUtil._
import coursier.jvm.JavaHome
import coursier.util.Task
import coursier.jvm.JvmIndex
import coursier.jvm.JvmCache
import scala.util.Properties

// format: off
final case class DefaultOptions(
  @Recurse
    logging: LoggingOptions = LoggingOptions(),
  @Recurse
    compilationServer: SharedCompilationServerOptions = SharedCompilationServerOptions(),
  @Recurse
    directories: SharedDirectoriesOptions = SharedDirectoriesOptions(),
  @Recurse
    jvm: SharedJvmOptions = SharedJvmOptions(),
  @Recurse
    coursier: CoursierOptions = CoursierOptions()
) {
  // format: on

  def bloopRifleConfig: BloopRifleConfig =
    DefaultOptions.bloopRifleConfig(
      jvm,
      compilationServer,
      logging,
      coursier,
      directories
    )

}

object DefaultOptions {
  implicit lazy val parser: Parser[DefaultOptions] = Parser.derive
  implicit lazy val help: Help[DefaultOptions]     = Help.derive

  def bloopRifleConfig(
    jvm: SharedJvmOptions,
    compilationServer: SharedCompilationServerOptions,
    logging: LoggingOptions,
    coursier: CoursierOptions,
    directories: SharedDirectoriesOptions
  ): BloopRifleConfig = {

    def finalJvmIndexOs = jvm.jvmIndexOs.getOrElse(OsLibc.jvmIndexOs)

    val coursierCache = coursier.coursierCache(logging.logger.coursierLogger(""))

    def javaHomeManager(
      archiveCache: ArchiveCache[Task],
      cache: FileCache[Task],
      verbosity: Int
    ): JavaHome = {
      val indexUrl = jvm.jvmIndex.getOrElse(JvmIndex.coursierIndexUrl)
      val indexTask = {
        val msg    = if (verbosity > 0) "Downloading JVM index" else ""
        val cache0 = cache.withMessage(msg)
        cache0.logger.using {
          JvmIndex.load(cache0, indexUrl)
        }
      }
      val jvmCache = JvmCache()
        .withIndex(indexTask)
        .withArchiveCache(
          archiveCache.withCache(
            cache.withMessage("Downloading JVM")
          )
        )
        .withOs(finalJvmIndexOs)
        .withArchitecture(jvm.jvmIndexArch.getOrElse(JvmIndex.defaultArchitecture()))
      JavaHome().withCache(jvmCache)
    }

    def javaHomeLocationOpt(): Option[os.Path] =
      jvm.javaHome.map(os.Path(_, os.pwd))
        .orElse {
          if (jvm.jvm.isEmpty)
            Option(System.getenv("JAVA_HOME"))
              .map(p => os.Path(p, os.pwd))
              .orElse(sys.props.get("java.home").map(p => os.Path(p, os.pwd)))
          else None
        }
        .orElse {
          val verbosity = 0
          jvm.jvm.map { jvmId =>
            implicit val ec: ExecutionContextExecutorService = coursierCache.ec
            coursierCache.logger.use {
              val enforceLiberica =
                finalJvmIndexOs == "linux-musl" &&
                jvmId.forall(c => c.isDigit || c == '.' || c == '-')
              val jvmId0 =
                if (enforceLiberica)
                  s"liberica:$jvmId" // FIXME Workaround, until this is automatically handled by coursier-jvm
                else
                  jvmId
              val javaHomeManager0 = javaHomeManager(ArchiveCache(), coursierCache, verbosity)
                .withMessage(s"Downloading JVM $jvmId0")
              val path =
                try javaHomeManager0.get(jvmId0).unsafeRun()
                catch {
                  case NonFatal(e) => throw new Exception(e)
                }
              os.Path(path)
            }
          }
        }

    def downloadJvm(jvmId: String): String = {
      implicit val ec: ExecutionContextExecutorService = coursierCache.ec
      val javaHomeManager0 = javaHomeManager(ArchiveCache(), coursierCache, logging.verbosity)
        .withMessage(s"Downloading JVM $jvmId")
      val command = {
        val path = coursierCache.logger.use {
          try javaHomeManager0.get(jvmId).unsafeRun()
          catch {
            case NonFatal(e) => throw new Exception(e)
          }
        }
        os.Path(path)
      }
      val ext = if (Properties.isWin) ".exe" else ""
      (command / "bin" / s"java$ext").toString
    }

    lazy val defaultJvmCmd =
      downloadJvm(OsLibc.baseDefaultJvm(OsLibc.jvmIndexOs, "17"))
    val javaCmd = jvm.jvm
      .map(downloadJvm)
      .orElse {
        for (javaHome <- javaHomeLocationOpt()) yield {
          val (javaHomeVersion, javaHomeCmd) = OsLibc.javaHomeVersion(javaHome)
          if (javaHomeVersion >= 17) javaHomeCmd
          else defaultJvmCmd
        }
      }
      .getOrElse(defaultJvmCmd)

    compilationServer.bloopRifleConfig(
      logging.logger,
      coursierCache,
      javaCmd,
      directories.directories,
      Some(17)
    )
  }

}
