import $ivy.`com.lihaoyi::mill-contrib-jmh:$MILL_VERSION`
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.21`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.21`

import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._
import mill.contrib.jmh.JmhModule
import mill.scalalib._
import mill.scalalib.publish.PublishInfo

import java.io.File

import scala.concurrent.duration.{Duration, DurationInt}

object Dependencies {
  def scala212 = "2.12.18"
  def scala213 = "2.13.12"

  def scalaVersions = Seq(scala212, scala213)

  def serverScalaVersion = scala212

  def asmVersion         = "9.6"
  def coursierVersion    = "2.1.0-M6-53-gb4f448130"
  def graalvmVersion     = "22.2.0"
  def jsoniterVersion    = "2.13.3.2"
  def scalaJs1Version    = "1.14.0"
  def scalaJsEnvsVersion = "1.1.1"

  def asm               = ivy"org.ow2.asm:asm:$asmVersion"
  def asmUtil           = ivy"org.ow2.asm:asm-util:$asmVersion"
  def bloopConfig       = ivy"ch.epfl.scala::bloop-config:1.5.5"
  def brave             = ivy"io.zipkin.brave:brave:5.16.0"
  def bsp4j             = ivy"ch.epfl.scala:bsp4j:2.1.0-M7"
  def bsp4s             = ivy"ch.epfl.scala::bsp4s:2.1.0-M7"
  def caseApp           = ivy"com.github.alexarchambault::case-app:2.0.6"
  def caseApp21         = ivy"com.github.alexarchambault::case-app:2.1.0-M15"
  def collectionCompat  = ivy"org.scala-lang.modules::scala-collection-compat:2.9.0"
  def coursier          = ivy"io.get-coursier::coursier:$coursierVersion"
  def coursierInterface = ivy"io.get-coursier:interface:1.0.13"
  def coursierJvm       = ivy"io.get-coursier::coursier-jvm:$coursierVersion"
  def dependency        = ivy"io.get-coursier::dependency:0.2.2"
  def difflib           = ivy"com.googlecode.java-diff-utils:diffutils:1.3.0"
  def directoryWatcher  = ivy"ch.epfl.scala:directory-watcher:0.8.0+6-f651bd93"
  def expecty           = ivy"com.eed3si9n.expecty::expecty:0.15.4"
  def jsoniterCore =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:$jsoniterVersion"
  def jsoniterMacros =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:$jsoniterVersion"
  def jsonrpc4s              = ivy"io.github.alexarchambault.bleep::jsonrpc4s:0.1.1"
  def junit                  = ivy"com.github.sbt:junit-interface:0.13.3"
  def libdaemonjvm           = ivy"io.github.alexarchambault.libdaemon::libdaemon:0.0.11"
  def libraryManagement      = ivy"org.scala-sbt::librarymanagement-ivy:1.9.3"
  def log4j                  = ivy"org.apache.logging.log4j:log4j-core:2.21.1"
  def logback                = ivy"ch.qos.logback:logback-classic:1.4.6"
  def macroParadise          = ivy"org.scalamacros:::paradise:2.1.1"
  def monix                  = ivy"io.monix::monix:3.2.0"
  def munit                  = ivy"org.scalameta::munit:0.7.29"
  def nailgun                = ivy"io.github.alexarchambault.bleep:nailgun-server:1.0.7"
  def osLib                  = ivy"com.lihaoyi::os-lib:0.9.0"
  def pprint                 = ivy"com.lihaoyi::pprint:0.8.1"
  def sbtTestAgent           = ivy"org.scala-sbt:test-agent:1.9.7"
  def sbtTestInterface       = ivy"org.scala-sbt:test-interface:1.0"
  def scalaDebugAdapter      = ivy"ch.epfl.scala::scala-debug-adapter:3.1.4"
  def scalaJsLinker1         = ivy"org.scala-js::scalajs-linker:$scalaJs1Version"
  def scalaJsEnvs1           = ivy"org.scala-js::scalajs-js-envs:$scalaJsEnvsVersion"
  def scalaJsEnvNode1        = ivy"org.scala-js::scalajs-env-nodejs:$scalaJsEnvsVersion"
  def scalaJsEnvJsdomNode1   = ivy"org.scala-js::scalajs-env-jsdom-nodejs:1.1.0"
  def scalaJsSbtTestAdapter1 = ivy"org.scala-js::scalajs-sbt-test-adapter:$scalaJs1Version"
  def scalaJsLogging1        = ivy"org.scala-js::scalajs-logging:1.1.1"
  def scalaNativeTools04     = ivy"org.scala-native::tools:0.4.16"
  def scalazCore             = ivy"org.scalaz::scalaz-core:7.3.7"
  def snailgun      = ivy"io.github.alexarchambault.scala-cli.snailgun::snailgun-core:0.4.1-sc2"
  def sourcecode    = ivy"com.lihaoyi::sourcecode:0.3.1"
  def svm           = ivy"org.graalvm.nativeimage:svm:$graalvmVersion"
  def utest         = ivy"com.lihaoyi::utest:0.8.2"
  def xxHashLibrary = ivy"net.jpountz.lz4:lz4:1.3.0"
  def zinc          = ivy"org.scala-sbt::zinc:1.9.5"
  def zipkinSender  = ivy"io.zipkin.reporter2:zipkin-sender-urlconnection:2.16.4"
  def zt            = ivy"org.zeroturnaround:zt-zip:1.16"

  def graalVmId = s"graalvm-java17:$graalvmVersion"
}

object internal extends Module {
  private def computePublishVersion(state: VcsState, simple: Boolean): String =
    if (state.commitsSinceLastTag > 0)
      if (simple) {
        val versionOrEmpty = state.lastTag
          .filter(_ != "latest")
          .filter(_ != "nightly")
          .map(_.stripPrefix("v"))
          .flatMap { tag =>
            if (simple) {
              val idx = tag.lastIndexOf(".")
              if (idx >= 0) {
                val (num, rest) = {
                  val incrPart = tag.drop(idx + 1)
                  val idx0     = incrPart.indexWhere(!_.isDigit)
                  assert(idx0 > 0)
                  (incrPart.take(idx0).toInt, incrPart.drop(idx0))
                }
                Some(tag.take(idx + 1) + (num + 1).toString + rest + "-SNAPSHOT")
              }
              else
                None
            }
            else {
              val idx = tag.indexOf("-")
              if (idx >= 0) Some(tag.take(idx) + "+" + tag.drop(idx + 1) + "-SNAPSHOT")
              else None
            }
          }
          .getOrElse("0.0.1-SNAPSHOT")
        Some(versionOrEmpty)
          .filter(_.nonEmpty)
          .getOrElse(state.format())
      }
      else {
        val rawVersion = os.proc("git", "describe", "--tags").call().out.text().trim
          .stripPrefix("v")
          .replace("latest", "0.0.0")
          .replace("nightly", "0.0.0")
        val idx = rawVersion.indexOf("-")
        if (idx >= 0) rawVersion.take(idx) + "+" + rawVersion.drop(idx + 1) + "-SNAPSHOT"
        else rawVersion
      }
    else {
      val fromTag = state
        .lastTag
        .getOrElse(state.format())
        .stripPrefix("v")
      if (fromTag == "0.0.0") "0.0.1-SNAPSHOT"
      else fromTag
    }

  def finalPublishVersion = {
    val isCI = System.getenv("CI") != null
    if (isCI)
      T.persistent {
        val state = VcsVersion.vcsState()
        computePublishVersion(state, simple = false)
      }
    else
      T {
        val state = VcsVersion.vcsState()
        computePublishVersion(state, simple = true)
      }
  }

  def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
    T.command {
      val timeout     = 10.minutes
      val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
      val pgpPassword = sys.env("PGP_PASSWORD")
      val data        = define.Target.sequence(tasks.value)()

      doPublishSonatype(
        credentials = credentials,
        pgpPassword = pgpPassword,
        data = data,
        timeout = timeout,
        log = T.ctx().log
      )
    }

  private def doPublishSonatype(
    credentials: String,
    pgpPassword: String,
    data: Seq[PublishModule.PublishData],
    timeout: Duration,
    log: mill.api.Logger
  ): Unit = {

    val artifacts = data.map {
      case PublishModule.PublishData(a, s) =>
        (s.map { case (p, f) => (p.path, f) }, a)
    }

    val isRelease = {
      val versions = artifacts.map(_._2.version).toSet
      val set      = versions.map(!_.endsWith("-SNAPSHOT"))
      assert(
        set.size == 1,
        s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
      )
      set.head
    }
    val publisher = new publish.SonatypePublisher(
      uri = "https://s01.oss.sonatype.org/service/local",
      snapshotUri = "https://s01.oss.sonatype.org/content/repositories/snapshots",
      credentials = credentials,
      signed = true,
      gpgArgs = Seq(
        "--detach-sign",
        "--batch=true",
        "--yes",
        "--pinentry-mode",
        "loopback",
        "--passphrase",
        pgpPassword,
        "--armor",
        "--use-agent"
      ),
      readTimeout = timeout.toMillis.toInt,
      connectTimeout = timeout.toMillis.toInt,
      log = log,
      awaitTimeout = timeout.toMillis.toInt,
      stagingRelease = isRelease
    )

    publisher.publishAll(isRelease, artifacts: _*)
  }
}

trait BloopPublish extends PublishModule with PublishLocalNoFluff {
  import mill.scalalib.publish._
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "io.github.alexarchambault.bleep",
    url = "https://github.com/scala-cli/bloop-core",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("scala-cli", "bloop-core"),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    )
  )
  def publishVersion =
    internal.finalPublishVersion()
}

trait BloopCrossSbtModule extends CrossSbtModule {
  def scalacOptions = T {
    val sv = scalaVersion()
    val extraOptions =
      if (sv.startsWith("2.11")) Seq("-Ywarn-unused-import")
      else if (sv.startsWith("2.12")) Seq("-Ywarn-unused", "-Xlint:unused")
      else if (sv.startsWith("2.13")) Seq("-Wunused")
      else Nil
    super.scalacOptions() ++ extraOptions
  }
}

def emptyZip = T {
  import java.io._
  import java.util.zip._
  val dest = T.dest / "empty.zip"
  val baos = new ByteArrayOutputStream
  val zos  = new ZipOutputStream(baos)
  zos.finish()
  zos.close()
  os.write(dest, baos.toByteArray)
  PathRef(dest)
}

trait PublishLocalNoFluff extends mill.scalalib.PublishModule {
  // adapted from https://github.com/com-lihaoyi/mill/blob/fea79f0515dda1def83500f0f49993e93338c3de/scalalib/src/PublishModule.scala#L70-L85
  // writes empty zips as source and doc JARs
  def publishLocalNoFluff(localIvyRepo: String = null): define.Command[PathRef] = T.command {

    import mill.scalalib.publish.LocalIvyPublisher
    val publisher = localIvyRepo match {
      case null => LocalIvyPublisher
      case repo =>
        new LocalIvyPublisher(os.Path(repo.replace("{VERSION}", publishVersion()), os.pwd))
    }

    publisher.publish(
      jar = jar().path,
      sourcesJar = emptyZip().path,
      docJar = emptyZip().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifactMetadata(),
      extras = extraPublish()
    )

    jar()
  }
}

object shared extends Cross[Shared](Dependencies.scalaVersions: _*)
class Shared(val crossScalaVersion: String) extends BloopCrossSbtModule with BloopPublish {
  def artifactName = "bloop-shared"
  def compileIvyDeps = super.compileIvyDeps() ++ Agg(
    Dependencies.jsoniterMacros
  )
  def docJar = T {
    emptyZip()
  }
  def ivyDeps = super.ivyDeps() ++ Agg(
    Dependencies.bsp4s
      .exclude(("com.github.plokhotnyuk.jsoniter-scala", "*"))
      .exclude(("me.vican.jorge", "jsonrpc4s_2.12"))
      .exclude(("me.vican.jorge", "jsonrpc4s_2.13")),
    Dependencies.jsonrpc4s,
    Dependencies.coursierInterface,
    Dependencies.jsoniterCore,
    Dependencies.log4j,
    Dependencies.sbtTestAgent,
    Dependencies.sbtTestInterface,
    Dependencies.xxHashLibrary,
    Dependencies.zinc
  )
}

object backend extends Cross[Backend](Dependencies.scalaVersions: _*)
class Backend(val crossScalaVersion: String) extends BloopCrossSbtModule with BloopPublish {
  def artifactName = "bloop-backend"
  def moduleDeps = super.moduleDeps ++ Seq(
    shared()
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Dependencies.asm,
    Dependencies.asmUtil,
    Dependencies.brave,
    Dependencies.difflib,
    Dependencies.directoryWatcher,
    Dependencies.libraryManagement,
    Dependencies.monix,
    Dependencies.nailgun,
    Dependencies.pprint,
    Dependencies.scalazCore,
    Dependencies.sourcecode,
    Dependencies.zipkinSender,
    Dependencies.zt
  )

  def buildInfoFile = T.persistent {
    val dir  = T.dest / "constants"
    val dest = dir / "BloopScalaInfo.scala"
    val code =
      s"""package bloop.internal.build
         |
         |/** Build-time constants. Generated by mill. */
         |object BloopScalaInfo {
         |  def scalaVersion = "$crossScalaVersion"
         |  def scalaOrganization = "org.scala-lang"
         |}
         |""".stripMargin
    if (!os.isFile(dest) || os.read(dest) != code)
      os.write.over(dest, code, createFolders = true)
    PathRef(dir)
  }
  def generatedSources = super.generatedSources() ++ Seq(buildInfoFile())

  def extraPublish = super.extraPublish() ++ Seq(
    PublishInfo(file = test.jar(), classifier = Some("tests"), ivyConfig = "test")
  )

  object test extends Tests {
    def ivyDeps = super.ivyDeps() ++ Agg(
      Dependencies.junit,
      Dependencies.utest
    )
    def testFramework = "utest.runner.Framework"
  }
}

def escapePath(path: os.Path): String =
  path.toString.replace("\\", "\\\\")

object frontend extends Cross[Frontend](Dependencies.scalaVersions: _*)
class Frontend(val crossScalaVersion: String) extends BloopCrossSbtModule with BloopPublish {
  def artifactName = "bloop-frontend"
  def moduleDeps = super.moduleDeps ++ Seq(
    backend()
  )
  def compileIvyDeps = super.compileIvyDeps() ++ Agg(
    Dependencies.jsoniterMacros
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Dependencies.bloopConfig,
    Dependencies.caseApp,
    Dependencies.libdaemonjvm,
    Dependencies.logback,
    Dependencies.scalaDebugAdapter
  )

  def mainClass = Some("bloop.Cli")

  def buildInfoFile = T.persistent {
    val dir  = T.dest / "constants"
    val dest = dir / "BuildInfo.scala"
    val code =
      s"""package bloop.internal.build
         |
         |/** Build-time constants. Generated by mill. */
         |object BuildInfo {
         |  def organization = "${pomSettings().organization}"
         |  def bloopName = "bloop"
         |  def scalaVersion = "${scalaVersion()}"
         |  def version = "${publishVersion()}"
         |  def nailgunClientLocation = "${escapePath(
          os.pwd / "frontend" / "src" / "test" / "resources" / "pynailgun" / "ng.py"
        )}"
         |  def bspVersion = "${Dependencies.bsp4s.dep.version}"
         |  def zincVersion = "${Dependencies.zinc.dep.version}"
         |  def snailgunVersion = "0.4.1-sc2"
         |  def nativeBridge04 = "${bridges.`scala-native-04`().artifactId()}"
         |  def jsBridge1 = "${bridges.`scalajs-1`().artifactId()}"
         |}
         |""".stripMargin
    if (!os.isFile(dest) || os.read(dest) != code)
      os.write.over(dest, code, createFolders = true)
    PathRef(dir)
  }
  def generatedSources = super.generatedSources() ++ Seq(buildInfoFile())

  def extraJvmArgs = Seq(
    "-Xmx3g",
    "-Xms1g",
    "-XX:ReservedCodeCacheSize=512m",
    "-XX:MaxInlineLevel=20"
  )

  def forkArgs = super.forkArgs() ++ extraJvmArgs

  def extraPublish = super.extraPublish() ++ Seq(
    PublishInfo(file = test.jar(), classifier = Some("tests"), ivyConfig = "test")
  )

  // TODO Publish test artifact too

  trait Tests extends super.Tests {
    def ivyDeps = super.ivyDeps() ++ Agg(
      Dependencies.junit,
      Dependencies.utest
    )
    def testFramework = "utest.runner.Framework"

    def forkArgs = super.forkArgs() ++ extraJvmArgs
  }

  object test extends Tests {
    def moduleDeps = super.moduleDeps ++ Seq(
      backend().test
    )
    def compileIvyDeps = super.compileIvyDeps() ++ Agg(
      Dependencies.jsoniterMacros
    )
    def ivyDeps = super.ivyDeps() ++ Agg(
      Dependencies.osLib
    )

    def buildInfoFile = T.persistent {
      val dir  = T.dest / "constants"
      val dest = dir / "BuildTestInfo.scala"
      val junitCp = upstreamAssemblyClasspath()
        .map(_.path)
        .filter(f => f.last.contains("junit") || f.last.contains("hamcrest"))
        .map(f => "\"" + escapePath(f) + "\"")
        .mkString("Seq(", ", ", ")")
      val code =
        s"""package bloop.internal.build
           |
           |/** Build-time constants. Generated by mill. */
           |object BuildTestInfo {
           |  def sampleSourceGenerator = "${escapePath(
            os.pwd / "frontend" / "src" / "test" / "resources" / "source-generator.py"
          )}"
           |  def junitTestJars = $junitCp
           |  def baseDirectory = "${escapePath(os.pwd)}"
           |}
           |""".stripMargin
      if (!os.isFile(dest) || os.read(dest) != code)
        os.write.over(dest, code, createFolders = true)
      PathRef(dir)
    }
    def generatedSources = super.generatedSources() ++ Seq(buildInfoFile())

    def forkArgs = T {
      val tmpDir = T.dest / "tmp-dir"
      super.forkArgs() :+ s"-Dbloop.tests.tmp-dir=$tmpDir"
    }
  }
  object it extends Tests {
    def moduleDeps = super.moduleDeps ++ Seq(
      `buildpress-config`()
    )
    def sources = T.sources(
      millSourcePath / "src" / "it" / "scala",
      millSourcePath / "src" / "it" / "java"
    )
  }
}

object `buildpress-config` extends Cross[BuildpressConfig](Dependencies.scalaVersions: _*)
class BuildpressConfig(val crossScalaVersion: String)
    extends BloopCrossSbtModule
    with BloopPublish {
  def compileIvyDeps = super.compileIvyDeps() ++ Agg(
    Dependencies.jsoniterMacros
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Dependencies.jsoniterCore
  )

  def scalacPluginIvyDeps = T {
    val sv = scalaVersion()
    val scala212Plugins =
      if (sv.startsWith("2.12.")) Agg(Dependencies.macroParadise)
      else Nil
    super.scalacPluginIvyDeps() ++ scala212Plugins
  }
}

object bridges extends Module {
  object `scalajs-1` extends Cross[Scalajs1](Dependencies.scalaVersions: _*)
  class Scalajs1(val crossScalaVersion: String) extends BloopCrossSbtModule with BloopPublish {
    def artifactName = "bloop-js-bridge-1"
    def compileModuleDeps = super.compileModuleDeps ++ Seq(
      frontend(),
      shared(),
      backend()
    )
    def compileIvyDeps = super.compileIvyDeps() ++ Agg(
      Dependencies.scalaJsLinker1,
      Dependencies.scalaJsLogging1,
      Dependencies.scalaJsEnvs1,
      Dependencies.scalaJsEnvNode1,
      Dependencies.scalaJsEnvJsdomNode1,
      Dependencies.scalaJsSbtTestAdapter1
    )

    object test extends Tests {
      def moduleDeps = super.moduleDeps ++ Seq(
        frontend().test
      )
      def ivyDeps = super.ivyDeps() ++ Agg(
        Dependencies.junit,
        Dependencies.utest
      )
      def testFramework = "utest.runner.Framework"
    }
  }

  object `scala-native-04` extends Cross[ScalaNative04](Dependencies.scalaVersions: _*)
  class ScalaNative04(val crossScalaVersion: String)
      extends BloopCrossSbtModule
      with BloopPublish {
    def artifactName = "bloop-native-bridge-0-4"

    private def updateSources(originalSources: Seq[PathRef]): Seq[PathRef] =
      if (millSourcePath.endsWith(os.rel / "scala-native-04")) {
        val updatedSourcePath = millSourcePath / os.up / "scala-native-0.4"
        originalSources.map {
          case pathRef if pathRef.path.startsWith(millSourcePath) =>
            PathRef(updatedSourcePath / pathRef.path.relativeTo(millSourcePath))
          case other => other
        }
      }
      else
        originalSources

    def sources = T.sources(updateSources(super.sources()))

    def compileModuleDeps = super.compileModuleDeps ++ Seq(
      frontend(),
      shared(),
      backend()
    )
    def compileIvyDeps = super.compileIvyDeps() ++ Agg(
      Dependencies.scalaNativeTools04
    )

    object test extends Tests {
      def sources = T.sources(updateSources(super.sources()))
      def moduleDeps = super.moduleDeps ++ Seq(
        frontend().test
      )
      def ivyDeps = super.ivyDeps() ++ Agg(
        Dependencies.junit,
        Dependencies.utest
      )
      def testFramework = "utest.runner.Framework"
    }
  }
}

object benchmarks extends Cross[Benchmarks](Dependencies.scalaVersions: _*)
class Benchmarks(val crossScalaVersion: String) extends BloopCrossSbtModule with JmhModule {
  def jmhCoreVersion = "1.21"

  def moduleDeps = super.moduleDeps ++ Seq(
    frontend().it
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"pl.project13.scala:sbt-jmh-extras:0.3.7"
  )

  def buildInfoFile = T.persistent {
    val dir  = T.dest / "constants"
    val dest = dir / "BuildInfo.scala"
    val code =
      s"""package bloop.benchmarks
         |
         |/** Build-time constants. Generated by mill. */
         |object BuildInfo {
         |  def resourceDirectory = "${escapePath(frontend().test.resources().head.path)}"
         |  def fullCompilationClasspath = Seq(${frontend()
          .runClasspath()
          .map(r => "\"" + escapePath(r.path) + "\"")
          .mkString(", ")})
         |}
         |""".stripMargin
    if (!os.isFile(dest) || os.read(dest) != code)
      os.write.over(dest, code, createFolders = true)
    PathRef(dir)
  }
  def generatedSources = super.generatedSources() ++ Seq(buildInfoFile())

  def forkArgs = T {
    def refOf(version: String) = {
      val HasSha = """(?:.+?)-([0-9a-f]{8})(?:\+\d{8}-\d{4})?""".r
      version match {
        case HasSha(sha) => sha
        case _           => version
      }
    }
    val version = frontend().publishVersion()
    val sbtLaunchJar = os
      .proc(
        "cs",
        "get",
        "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.9.2/sbt-launch-1.9.2.jar"
      )
      .call()
      .out
      .trim()
    super.forkArgs() ++ Seq(
      s"-Dsbt.launcher=$sbtLaunchJar",
      s"-DbloopVersion=$version",
      s"-DbloopRef=${refOf(version)}",
      s"-Dgit.localdir=${os.pwd}"
    )
  }
}

trait BloopCliModule extends ScalaModule with BloopPublish {
  def javacOptions = super.javacOptions() ++ Seq(
    "--release",
    "16"
  )
  def scalacOptions = T {
    val sv         = scalaVersion()
    val isScala213 = sv.startsWith("2.13.")
    val extraOptions =
      if (isScala213) Seq("-Xsource:3", "-Ytasty-reader")
      else Nil
    super.scalacOptions() ++ Seq("-Ywarn-unused") ++ extraOptions
  }
}

object `bloop-rifle` extends BloopCliModule {
  def scalaVersion = Dependencies.scala213
  def ivyDeps = super.ivyDeps() ++ Agg(
    Dependencies.bsp4j,
    Dependencies.collectionCompat,
    Dependencies.libdaemonjvm,
    Dependencies.snailgun
  )

  def constantsFile = T.persistent {
    val dir  = T.dest / "constants"
    val dest = dir / "Constants.scala"
    val code =
      s"""package bloop.rifle.internal
         |
         |/** Build-time constants. Generated by mill. */
         |object Constants {
         |  def bloopVersion = "${frontend(Dependencies.serverScalaVersion).publishVersion()}"
         |  def bloopScalaVersion = "${Dependencies.serverScalaVersion}"
         |  def bspVersion = "${Dependencies.bsp4j.dep.version}"
         |}
         |""".stripMargin
    if (!os.isFile(dest) || os.read(dest) != code)
      os.write.over(dest, code, createFolders = true)
    PathRef(dir)
  }
  def generatedSources = super.generatedSources() ++ Seq(constantsFile())

  object test extends Tests {
    def testFramework = "munit.Framework"
    def ivyDeps = super.ivyDeps() ++ Agg(
      Dependencies.expecty,
      Dependencies.munit
    )
  }
}

object cli extends BloopCliModule with NativeImage {
  def scalaVersion = Dependencies.scala213
  def moduleDeps = Seq(
    `bloop-rifle`
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Dependencies.caseApp21,
    Dependencies.coursier,
    Dependencies.coursierJvm,
    Dependencies.dependency,
    Dependencies.jsoniterCore,
    Dependencies.osLib
  )
  def compileIvyDeps = super.compileIvyDeps() ++ Agg(
    Dependencies.jsoniterMacros,
    Dependencies.svm
  )
  private def actualMainClass = "bloop.cli.Bloop"
  def mainClass               = Some(actualMainClass)

  def nativeImageMainClass    = actualMainClass
  def nativeImageClassPath    = runClasspath()
  def nativeImageGraalVmJvmId = Dependencies.graalVmId
  def nativeImageOptions = super.nativeImageOptions() ++ Seq(
    "--no-fallback",
    "--enable-url-protocols=http,https",
    "-Djdk.http.auth.tunneling.disabledSchemes="
  )
  def nativeImagePersist = System.getenv("CI") != null

  def copyToArtifacts(directory: String = "artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      "bloop",
      compress = true
    )
  }
}

def tmpDir = T {
  T.dest.toString
}

object integration extends BloopCliModule {
  def scalaVersion = Dependencies.scala213
  object test extends Tests {
    def testFramework = "munit.Framework"
    def ivyDeps = super.ivyDeps() ++ Agg(
      Dependencies.expecty,
      Dependencies.munit,
      Dependencies.osLib,
      Dependencies.pprint
    )

    private def repoRoot = os.pwd / "out" / "repo"
    def localRepo = T {
      val modules = Seq(
        frontend(Dependencies.serverScalaVersion),
        backend(Dependencies.serverScalaVersion),
        shared(Dependencies.serverScalaVersion)
      )
      os.remove.all(repoRoot)
      os.makeDir.all(repoRoot)
      val tasks = modules.map(_.publishLocalNoFluff(repoRoot.toString))
      define.Target.sequence(tasks)
    }

    def forkEnv = super.forkEnv() ++ Seq(
      "BLOOP_CLI_TESTS_TMP_DIR" -> tmpDir()
    )

    private final class TestHelper(launcherTask: T[PathRef]) {
      def test(args: String*) = {
        val argsTask = T.task {
          val launcher = launcherTask().path
          localRepo()
          val extraArgs = Seq(
            s"-Dtest.bloop-cli.path=$launcher",
            s"-Dtest.bloop-cli.repo=$repoRoot"
          )
          args ++ extraArgs
        }
        T.command {
          testTask(argsTask, T.task(Seq.empty[String]))()
        }
      }
    }

    def jvm(args: String*) =
      new TestHelper(cli.launcher).test(args: _*)
    def native(args: String*) =
      new TestHelper(cli.nativeImage).test(args: _*)
    def test(args: String*) =
      jvm(args: _*)
  }
}

object ci extends Module {
  def upload(directory: String = "artifacts/") = T.command {
    val version = cli.publishVersion()

    val path = os.Path(directory, os.pwd)
    val launchers = os
      .list(path)
      .filter(os.isFile(_))
      .map(path => path -> path.last)
    val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if (version.endsWith("-SNAPSHOT")) ("nightly", true)
      else ("v" + version, false)

    Upload.upload(
      "scala-cli",
      "bloop-core",
      ghToken,
      tag,
      dryRun = false,
      overwrite = overwriteAssets
    )(launchers: _*)
  }

  def copyJvm(jvm: String = Dependencies.graalVmId, dest: String = "jvm") = T.command {
    import sys.process._
    val command = os.proc(
      "cs",
      "java-home",
      "--jvm",
      jvm,
      "--update",
      "--ttl",
      "0"
    )
    val baseJavaHome = os.Path(command.call().out.text().trim, os.pwd)
    System.err.println(s"Initial Java home $baseJavaHome")
    val destJavaHome = os.Path(dest, os.pwd)
    os.copy(baseJavaHome, destJavaHome, createFolders = true)
    System.err.println(s"New Java home $destJavaHome")
    destJavaHome
  }
}
