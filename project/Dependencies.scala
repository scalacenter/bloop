package build

import sbt.librarymanagement.syntax.stringToOrganization
import sbt.librarymanagement.CrossVersion
import sbt.Provided

object Dependencies {
  val Scala211Version = "2.11.12"
  val Scala212Version = "2.12.20"
  val Scala213Version = "2.13.16"

  val SbtVersion = "1.5.8"

  // Keep in sync in BloopComponentCompiler
  val zincVersion = "1.10.7"

  val bspVersion = "2.1.1"

  val scalazVersion = "7.3.8"
  val lmVersion = "1.10.3"
  val configDirsVersion = "26"
  val caseAppVersion = "2.0.6"
  val coursierVersion = "2.1.24"
  val sourcecodeVersion = "0.4.2"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.10.7"
  val junitVersion = "0.13.3"
  val directoryWatcherVersion = "0.8.0+6-f651bd93"
  val monixVersion = "3.2.0"
  val jsoniterVersion = "2.13.3.2"
  val shapelessVersion = "2.3.4"
  val scalaNative04Version = "0.4.17"
  val scalaNative05Version = "0.5.6"
  val scalaJs06Version = "0.6.33"
  val scalaJs1Version = "1.18.2"
  val scalaJsEnvsVersion = "1.1.1"
  val xxHashVersion = "1.3.0"
  val ztVersion = "1.17"
  val difflibVersion = "1.3.0"
  val braveVersion = "5.18.1"
  val zipkinSenderVersion = "3.5.0"
  val jnaVersion = "5.16.0"
  val asmVersion = "9.7.1"
  val ztExecVersion = "1.12"
  val debugAdapterVersion = "4.2.3"
  val bloopConfigVersion = "2.3.2"
  val semanticdbVersion = "4.9.9"
  val zinc = "org.scala-sbt" %% "zinc" % zincVersion
  val bsp4s = "ch.epfl.scala" %% "bsp4s" % bspVersion
  val bsp4j = "ch.epfl.scala" % "bsp4j" % bspVersion
  val jsonrpc4s = "io.github.alexarchambault.bleep" %% "jsonrpc4s" % "0.1.1"

  val nailgun = "io.github.alexarchambault.bleep" % "nailgun-server" % "1.0.7"
  val bloopConfig = "ch.epfl.scala" %% "bloop-config" % bloopConfigVersion

  val libraryManagement = "org.scala-sbt" %% "librarymanagement-ivy" % lmVersion
  val log4j = "org.apache.logging.log4j" % "log4j-core" % "2.23.0"
  val scalazCore = "org.scalaz" %% "scalaz-core" % scalazVersion
  val coursierInterface = "io.get-coursier" % "interface" % "1.0.28"
  val configDirectories = "io.get-coursier.util" % "directories-jni" % "0.1.2"
  val coursierInterfaceSubs = "io.get-coursier" % "interface-svm-subs" % "1.0.19"
  val svm = "org.graalvm.nativeimage" % "svm" % "24.1.2"
  val coursier = "io.get-coursier" %% "coursier" % coursierVersion
  val coursierJvm = "io.get-coursier" %% "coursier-jvm" % coursierVersion
  val dependency = "io.get-coursier" %% "dependency" % "0.3.2"
  val directoriesJni = "io.get-coursier.util" % "directories-jni" % "0.1.3"
  val scalaCollectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.13.0"
  val shapeless = "com.chuusai" %% "shapeless" % shapelessVersion
  val caseApp = "com.github.alexarchambault" %% "case-app" % caseAppVersion
  val caseApp21 = "com.github.alexarchambault" %% "case-app" % "2.1.0-M30"
  val sourcecode = "com.lihaoyi" %% "sourcecode" % sourcecodeVersion
  val sbtTestInterface = "org.scala-sbt" % "test-interface" % sbtTestInterfaceVersion
  val sbtTestAgent = "org.scala-sbt" % "test-agent" % sbtTestAgentVersion
  val snailgun = "io.github.alexarchambault.scala-cli.snailgun" %% "snailgun-core" % "0.4.1-sc2"
  val ztExec = "org.zeroturnaround" % "zt-exec" % ztExecVersion
  val logback = "ch.qos.logback" % "logback-classic" % "1.5.16"
  val libdaemonjvm = "ch.epfl.scala" %% "libdaemon" % "0.0.12"
  val scopt = "com.github.scopt" % "scopt_2.12" % "4.0.0-RC2"

  val utest = "com.lihaoyi" %% "utest" % "0.8.5"
  def munit = "org.scalameta" %% "munit" % "1.1.0"
  def expecty = "com.eed3si9n.expecty" %% "expecty" % "0.17.0"
  val pprint = "com.lihaoyi" %% "pprint" % "0.9.0"
  val oslib = "com.lihaoyi" %% "os-lib" % "0.11.4"
  val junit = "com.github.sbt" % "junit-interface" % junitVersion
  val directoryWatcher = "ch.epfl.scala" % "directory-watcher" % directoryWatcherVersion
  val difflib = "com.googlecode.java-diff-utils" % "diffutils" % difflibVersion

  val monix = "io.monix" %% "monix" % monixVersion
  val jsoniterCore =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion
  val jsoniterMacros =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion
  val scalaDebugAdapter = "ch.epfl.scala" %% "scala-debug-adapter" % debugAdapterVersion

  val scalaNativeTools04 = "org.scala-native" %% "tools" % scalaNative04Version % Provided
  val scalaNativeTools05 = "org.scala-native" %% "tools" % scalaNative05Version % Provided
  val scalaJsTools06 = "org.scala-js" %% "scalajs-tools" % scalaJs06Version % Provided
  val scalaJsSbtTestAdapter06 =
    "org.scala-js" %% "scalajs-sbt-test-adapter" % scalaJs06Version % Provided
  val scalaJsEnvs06 = "org.scala-js" %% "scalajs-js-envs" % scalaJs06Version % Provided

  val scalaJsLinker1 = "org.scala-js" %% "scalajs-linker" % scalaJs1Version % Provided
  val scalaJsEnvs1 = "org.scala-js" %% "scalajs-js-envs" % scalaJsEnvsVersion % Provided
  val scalaJsEnvNode1 = "org.scala-js" %% "scalajs-env-nodejs" % scalaJsEnvsVersion % Provided
  val scalaJsEnvJsdomNode1 = "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.0" % Provided
  val scalaJsSbtTestAdapter1 =
    "org.scala-js" %% "scalajs-sbt-test-adapter" % scalaJs1Version % Provided
  val scalaJsLogging1 = "org.scala-js" %% "scalajs-logging" % "1.1.1" % Provided
  val semanticdb = "org.scalameta" % "semanticdb" % semanticdbVersion cross CrossVersion.full

  val xxHashLibrary = "net.jpountz.lz4" % "lz4" % xxHashVersion
  val zt = "org.zeroturnaround" % "zt-zip" % ztVersion

  val brave = "io.zipkin.brave" % "brave" % braveVersion
  val zipkinSender = "io.zipkin.reporter2" % "zipkin-sender-urlconnection" % zipkinSenderVersion

  val jna = "net.java.dev.jna" % "jna" % jnaVersion
  val jnaPlatform = "net.java.dev.jna" % "jna-platform" % jnaVersion
  val asm = "org.ow2.asm" % "asm" % asmVersion
  val asmUtil = "org.ow2.asm" % "asm-util" % asmVersion
}
