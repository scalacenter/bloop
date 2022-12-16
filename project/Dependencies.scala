package build

import sbt.librarymanagement.syntax.stringToOrganization
import sbt.Provided

object Dependencies {
  val Scala211Version = "2.11.12"
  val Scala212Version = "2.12.17"
  val Scala213Version = "2.13.8"

  val SbtVersion = "1.3.3"

  val nailgunVersion = "ee3c4343"
  // Used to download the python client instead of resolving
  val nailgunCommit = "a2520c1e"

  // Keep in sync in BloopComponentCompiler
  val zincVersion = "1.8.0"

  val bspVersion = "2.1.0-M3"

  val scalazVersion = "7.2.35"
  val lmVersion = "1.8.0"
  val configDirsVersion = "26"
  val caseAppVersion = "2.0.6"
  val sourcecodeVersion = "0.3.0"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.8.0"
  val junitVersion = "0.13.3"
  val directoryWatcherVersion = "0.8.0+6-f651bd93"
  val monixVersion = "3.2.2"
  val jsoniterVersion = "2.13.3.2"
  val shapelessVersion = "2.3.4"
  val scalaNative04Version = "0.4.9"
  val scalaJs06Version = "0.6.33"
  val scalaJs1Version = "1.12.0"
  val scalaJsEnvsVersion = "1.1.1"
  val xxHashVersion = "1.3.0"
  val ztVersion = "1.13"
  val difflibVersion = "1.3.0"
  val braveVersion = "5.14.1"
  val zipkinSenderVersion = "2.16.3"
  val jnaVersion = "5.12.1"
  val asmVersion = "9.4"
  val snailgunVersion = "0.4.0"
  val ztExecVersion = "1.12"
  val debugAdapterVersion = "3.0.5"
  val bloopConfigVersion = "1.5.5"

  val zinc = "org.scala-sbt" %% "zinc" % zincVersion
  val bsp4s = "ch.epfl.scala" %% "bsp4s" % bspVersion
  val bsp4j = "ch.epfl.scala" % "bsp4j" % bspVersion
  val nailgun = "ch.epfl.scala" % "nailgun-server" % nailgunVersion
  val bloopConfig = "ch.epfl.scala" %% "bloop-config" % bloopConfigVersion

  val configDirectories = "dev.dirs" % "directories" % configDirsVersion
  val libraryManagement = "org.scala-sbt" %% "librarymanagement-ivy" % lmVersion
  val log4j = "org.apache.logging.log4j" % "log4j-core" % "2.19.0"
  val scalazCore = "org.scalaz" %% "scalaz-core" % scalazVersion
  val scalazConcurrent = "org.scalaz" %% "scalaz-concurrent" % scalazVersion
  val coursierInterface = "io.get-coursier" % "interface" % "1.0.6"
  val coursierInterfaceSubs = "io.get-coursier" % "interface-svm-subs" % "1.0.6"
  val scalaCollectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.2"
  val shapeless = "com.chuusai" %% "shapeless" % shapelessVersion
  val caseApp = "com.github.alexarchambault" %% "case-app" % caseAppVersion
  val sourcecode = "com.lihaoyi" %% "sourcecode" % sourcecodeVersion
  val sbtTestInterface = "org.scala-sbt" % "test-interface" % sbtTestInterfaceVersion
  val sbtTestAgent = "org.scala-sbt" % "test-agent" % sbtTestAgentVersion
  val snailgun = ("me.vican.jorge" %% "snailgun-cli" % snailgunVersion)
  val ztExec = "org.zeroturnaround" % "zt-exec" % ztExecVersion
  val slf4jNop = "org.slf4j" % "slf4j-nop" % "1.7.36"

  val utest = "com.lihaoyi" %% "utest" % "0.8.1"
  val pprint = "com.lihaoyi" %% "pprint" % "0.8.1"
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

  val xxHashLibrary = "net.jpountz.lz4" % "lz4" % xxHashVersion
  val zt = "org.zeroturnaround" % "zt-zip" % ztVersion

  val brave = "io.zipkin.brave" % "brave" % braveVersion
  val zipkinSender = "io.zipkin.reporter2" % "zipkin-sender-urlconnection" % zipkinSenderVersion

  val jna = "net.java.dev.jna" % "jna" % jnaVersion
  val jnaPlatform = "net.java.dev.jna" % "jna-platform" % jnaVersion
  val asm = "org.ow2.asm" % "asm" % asmVersion
  val asmUtil = "org.ow2.asm" % "asm-util" % asmVersion
}
