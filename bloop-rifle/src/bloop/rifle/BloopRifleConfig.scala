package bloop.rifle

import bloop.rifle.internal.Constants

import java.io.{File, InputStream, OutputStream}
import java.nio.file.Path

import scala.concurrent.duration._
import scala.util.Try

final case class BloopRifleConfig(
  address: BloopRifleConfig.Address,
  javaPath: String,
  javaOpts: Seq[String],
  classPath: String => Either[Throwable, (Seq[File], Boolean)],
  workingDir: File,
  bspSocketOrPort: Option[() => BspConnectionAddress],
  bspStdin: Option[InputStream],
  bspStdout: Option[OutputStream],
  bspStderr: Option[OutputStream],
  period: FiniteDuration,
  timeout: FiniteDuration,
  startCheckPeriod: FiniteDuration,
  startCheckTimeout: FiniteDuration,
  initTimeout: FiniteDuration,
  minimumBloopJvm: Int,
  retainedBloopVersion: BloopRifleConfig.BloopVersionConstraint
)

object BloopRifleConfig {

  sealed abstract class Address extends Product with Serializable {
    def render: String
  }

  object Address {
    final case class Tcp(host: String, port: Int) extends Address {
      def render = s"$host:$port"
    }
    final case class DomainSocket(path: Path) extends Address {
      def render = path.toString
      def outputPath: Path =
        path.resolve("output")
    }
  }

  sealed trait BloopVersionConstraint { def version: BloopVersion }
  case class AtLeast(version: BloopVersion) extends BloopVersionConstraint
  case class Strict(version: BloopVersion)  extends BloopVersionConstraint

  def hardCodedDefaultHost = "127.0.0.1"
  def hardCodedDefaultPort = 8212

  lazy val defaultHost: String = {
    val fromEnv   = Option(System.getenv("BLOOP_SERVER")).filter(_.nonEmpty)
    def fromProps = sys.props.get("bloop.server").filter(_.nonEmpty)
    fromEnv
      .orElse(fromProps)
      .getOrElse(hardCodedDefaultHost)
  }
  lazy val defaultPort: Int = {
    val fromEnv = Option(System.getenv("BLOOP_PORT"))
      .filter(_.nonEmpty)
      .flatMap(s => Try(s.toInt).toOption)
    def fromProps = sys.props.get("bloop.port")
      .filter(_.nonEmpty)
      .flatMap(s => Try(s.toInt).toOption)
    fromEnv
      .orElse(fromProps)
      .getOrElse(hardCodedDefaultPort)
  }

  // from https://github.com/scalacenter/bloop/blob/cbddb8baaf639a4e08ee630f1ebc559dc70255a8/bloopgun/src/main/scala/bloop/bloopgun/util/Environment.scala#L89-L93
  def hardCodedDefaultJavaOpts: Seq[String] =
    Seq(
      "-Xss4m",
      "-XX:MaxInlineLevel=20", // Specific option for faster C2, ignored by GraalVM
      "-XX:+UseZGC", // ZGC returns unused memory back to the OS, so Bloop does not occupy so much memory if unused
      "-XX:ZUncommitDelay=30",
      "-XX:ZCollectionInterval=5",
      "-Dbloop.ignore-sig-int=true"
    )

  lazy val defaultJavaOpts: Seq[String] = {
    val fromEnv   = Option(System.getenv("BLOOP_JAVA_OPTS")).filter(_.nonEmpty)
    def fromProps = sys.props.get("bloop.java-opts").filter(_.nonEmpty)
    fromEnv
      .orElse(fromProps)
      .map(_.split("\\s+").toSeq)
      .getOrElse(hardCodedDefaultJavaOpts)
  }

  def scalaCliBloopOrg = "io.github.alexarchambault.bleep"
  def hardCodedDefaultModule: String =
    s"$scalaCliBloopOrg:bloop-frontend_2.12"
  def hardCodedDefaultVersion: String =
    Constants.bloopVersion
  def hardCodedDefaultScalaVersion: String =
    Constants.bloopScalaVersion

  lazy val defaultModule: String = {
    val fromEnv   = Option(System.getenv("BLOOP_MODULE")).map(_.trim).filter(_.nonEmpty)
    def fromProps = sys.props.get("bloop.module").map(_.trim).filter(_.nonEmpty)
    fromEnv
      .orElse(fromProps)
      .getOrElse(hardCodedDefaultModule)
  }
  lazy val defaultVersion: String = {
    val fromEnv   = Option(System.getenv("BLOOP_VERSION")).filter(_.nonEmpty)
    def fromProps = sys.props.get("bloop.version").filter(_.nonEmpty)
    fromEnv
      .orElse(fromProps)
      .getOrElse(hardCodedDefaultVersion)
  }
  lazy val defaultScalaVersion: String = {
    val fromEnv   = Option(System.getenv("BLOOP_SCALA_VERSION")).filter(_.nonEmpty)
    def fromProps = sys.props.get("bloop.scala-version").filter(_.nonEmpty)
    fromEnv
      .orElse(fromProps)
      .getOrElse(hardCodedDefaultScalaVersion)
  }

  def default(
    address: Address,
    bloopClassPath: String => Either[Throwable, (Seq[File], Boolean)],
    workingDir: File
  ): BloopRifleConfig =
    BloopRifleConfig(
      address = address,
      javaPath = "java",
      javaOpts = defaultJavaOpts,
      classPath = bloopClassPath,
      workingDir = workingDir,
      bspSocketOrPort = None,
      bspStdin = None,
      bspStdout = None,
      bspStderr = None,
      period = 100.milliseconds,
      timeout = 10.seconds,
      startCheckPeriod = 100.millis,
      startCheckTimeout = 1.minute,
      initTimeout = 30.seconds,
      minimumBloopJvm = 8,
      retainedBloopVersion = AtLeast(BloopVersion(Constants.bloopVersion))
    )
}
