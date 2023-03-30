import $ivy.`com.lihaoyi::mill-contrib-jmh:$MILL_VERSION`
import $ivy.`io.chris-kipp::mill-ci-release::0.1.5`

import io.kipp.mill.ci.release.CiReleaseModule
import mill._
import mill.contrib.jmh.JmhModule
import mill.scalalib._

import java.io.File

object Dependencies {
  def scala212 = "2.12.17"
  def scala213 = "2.13.8"

  def scalaVersions = Seq(scala212, scala213)

  def asmVersion = "9.5"
  def jsoniterVersion = "2.13.3.2"
  def scalaJs1Version = "1.13.0"
  def scalaJsEnvsVersion = "1.1.1"

  def asm = ivy"org.ow2.asm:asm:$asmVersion"
  def asmUtil = ivy"org.ow2.asm:asm-util:$asmVersion"
  def bloopConfig = ivy"ch.epfl.scala::bloop-config:1.5.5"
  def brave = ivy"io.zipkin.brave:brave:5.15.0"
  def bsp4s = ivy"ch.epfl.scala::bsp4s:2.1.0-M3"
  def caseApp = ivy"com.github.alexarchambault::case-app:2.0.6"
  def coursierInterface = ivy"io.get-coursier:interface:1.0.13"
  def difflib = ivy"com.googlecode.java-diff-utils:diffutils:1.3.0"
  def directoryWatcher = ivy"ch.epfl.scala:directory-watcher:0.8.0+6-f651bd93"
  def jsoniterCore =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:$jsoniterVersion"
  def jsoniterMacros =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:$jsoniterVersion"
  def junit = ivy"com.github.sbt:junit-interface:0.13.3"
  def libdaemonjvm = ivy"io.github.alexarchambault.libdaemon::libdaemon:0.0.11"
  def libraryManagement = ivy"org.scala-sbt::librarymanagement-ivy:1.8.0"
  def log4j = ivy"org.apache.logging.log4j:log4j-core:2.20.0"
  def logback = ivy"ch.qos.logback:logback-classic:1.4.6"
  def macroParadise = ivy"org.scalamacros:::paradise:2.1.1"
  def monix = ivy"io.monix::monix:3.2.0"
  def nailgun = ivy"io.github.alexarchambault.bleep:nailgun-server:1.0.6"
  def osLib = ivy"com.lihaoyi::os-lib:0.9.0"
  def pprint = ivy"com.lihaoyi::pprint:0.8.1"
  def sbtTestAgent = ivy"org.scala-sbt:test-agent:1.8.2"
  def sbtTestInterface = ivy"org.scala-sbt:test-interface:1.0"
  def scalaDebugAdapter = ivy"ch.epfl.scala::scala-debug-adapter:3.0.7"
  def scalaJsLinker1 = ivy"org.scala-js::scalajs-linker:$scalaJs1Version"
  def scalaJsEnvs1 = ivy"org.scala-js::scalajs-js-envs:$scalaJsEnvsVersion"
  def scalaJsEnvNode1 = ivy"org.scala-js::scalajs-env-nodejs:$scalaJsEnvsVersion"
  def scalaJsEnvJsdomNode1 = ivy"org.scala-js::scalajs-env-jsdom-nodejs:1.1.0"
  def scalaJsSbtTestAdapter1 = ivy"org.scala-js::scalajs-sbt-test-adapter:$scalaJs1Version"
  def scalaJsLogging1 = ivy"org.scala-js::scalajs-logging:1.1.1"
  def scalaNativeTools04 = ivy"org.scala-native::tools:0.4.11"
  def scalazCore = ivy"org.scalaz::scalaz-core:7.3.7"
  def sourcecode = ivy"com.lihaoyi::sourcecode:0.3.0"
  def utest = ivy"com.lihaoyi::utest:0.8.1"
  def xxHashLibrary = ivy"net.jpountz.lz4:lz4:1.3.0"
  def zinc = ivy"org.scala-sbt::zinc:1.8.0"
  def zipkinSender = ivy"io.zipkin.reporter2:zipkin-sender-urlconnection:2.16.3"
  def zt = ivy"org.zeroturnaround:zt-zip:1.15"
}

trait PublishModule extends CiReleaseModule {
  import io.kipp.mill.ci.release.SonatypeHost
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
  override def sonatypeHost = Some(SonatypeHost.s01)
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

object shared extends Cross[Shared](Dependencies.scalaVersions: _*)
class Shared(val crossScalaVersion: String) extends BloopCrossSbtModule with PublishModule {
  def artifactName = "bloop-shared"
  def compileIvyDeps = super.compileIvyDeps() ++ Agg(
    Dependencies.jsoniterMacros
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Dependencies.bsp4s.exclude(("com.github.plokhotnyuk.jsoniter-scala", "*")),
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
class Backend(val crossScalaVersion: String) extends BloopCrossSbtModule with PublishModule {
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
    val dir = T.dest / "constants"
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
class Frontend(val crossScalaVersion: String) extends BloopCrossSbtModule with PublishModule {
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
    val dir = T.dest / "constants"
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
         |  def nativeBridge04 = "${bridges.`scala-native-04`().artifactId}"
         |  def jsBridge1 = "${bridges.`scalajs-1`().artifactId}"
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
      val dir = T.dest / "constants"
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
    with PublishModule {
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
  class Scalajs1(val crossScalaVersion: String) extends BloopCrossSbtModule with PublishModule {
    def artifactName = "bloop-js-bridge-1"
    def compileModuleDeps = super.compileModuleDeps ++ Seq(
      frontend()
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
      with PublishModule {
    def artifactName = "bloop-native-bridge-0-4"

    private def updateSources(originalSources: Seq[PathRef]): Seq[PathRef] = {
      if (millSourcePath.endsWith(os.rel / "scala-native-04")) {
        val updatedSourcePath = millSourcePath / os.up / "scala-native-0.4"
        originalSources.map {
          case pathRef if pathRef.path.startsWith(millSourcePath) =>
            PathRef(updatedSourcePath / pathRef.path.relativeTo(millSourcePath))
          case other => other
        }
      } else
        originalSources
    }

    def sources = T.sources(updateSources(super.sources()))

    def compileModuleDeps = super.compileModuleDeps ++ Seq(
      frontend()
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
    val dir = T.dest / "constants"
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
        case _ => version
      }
    }
    val version = frontend().publishVersion()
    val sbtLaunchJar = os
      .proc(
        "cs",
        "get",
        "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.8.2/sbt-launch-1.8.2.jar"
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
