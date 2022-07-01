package build

object Dependencies {
  val Scala212Version = "2.12.15"
  val Scala213Version = "2.13.8"

  // Keep in sync in BloopComponentCompiler
  val zincVersion = "1.6.0"

  val bspVersion = "2.0.0-M13"
  val javaDebugVersion = "0.21.0+1-7f1080f1"

  val scalazVersion = "7.2.20"
  val lmVersion = "1.0.0"
  val caseAppVersion = "2.0.6"
  val sourcecodeVersion = "0.1.4"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.4.4"
  val junitVersion = "0.13.3"
  val directoryWatcherVersion = "0.8.0+6-f651bd93"
  val monixVersion = "2.3.3"
  val jsoniterVersion = "2.4.0"
  val shapelessVersion = "2.3.4"
  val scalaNative04Version = "0.4.0"
  val scalaJs06Version = "0.6.32"
  val scalaJs1Version = "1.3.1"
  val scalaJsEnvsVersion = "1.1.1"
  val xxHashVersion = "1.3.0"
  val ztVersion = "1.13"
  val difflibVersion = "1.3.0"
  val braveVersion = "5.6.1"
  val zipkinSenderVersion = "2.7.15"
  val asmVersion = "7.0"
  val snailgunVersion = "0.4.0"
  val ztExecVersion = "1.11"
  val debugAdapterVersion = "2.2.0-M2"
  val coursierInterfaceVersion = "1.0.6"

  import sbt.librarymanagement.syntax.stringToOrganization
  val zinc = "org.scala-sbt" %% "zinc" % zincVersion
  val bsp4s = "ch.epfl.scala" %% "bsp4s" % bspVersion
  val bsp4j = "ch.epfl.scala" % "bsp4j" % bspVersion
  val nailgun = "io.github.alexarchambault.bleep" % "nailgun-server" % "1.0.3"
  val javaDebug = "ch.epfl.scala" % "com-microsoft-java-debug-core" % javaDebugVersion

  val libraryManagement = "org.scala-sbt" %% "librarymanagement-ivy" % lmVersion
  val log4j = "org.apache.logging.log4j" % "log4j-core" % "2.17.1"
  val scalazCore = "org.scalaz" %% "scalaz-core" % scalazVersion
  val scalazConcurrent = "org.scalaz" %% "scalaz-concurrent" % scalazVersion
  val coursierInterface = "io.get-coursier" % "interface" % coursierInterfaceVersion
  val coursierInterfaceSubs = "io.get-coursier" % "interface-svm-subs" % coursierInterfaceVersion
  val scalaCollectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.2"
  val shapeless = "com.chuusai" %% "shapeless" % shapelessVersion
  val caseApp = "com.github.alexarchambault" %% "case-app" % caseAppVersion
  val sourcecode = "com.lihaoyi" %% "sourcecode" % sourcecodeVersion
  val sbtTestInterface = "org.scala-sbt" % "test-interface" % sbtTestInterfaceVersion
  val sbtTestAgent = "org.scala-sbt" % "test-agent" % sbtTestAgentVersion
  val snailgun = ("me.vican.jorge" %% "snailgun-cli" % snailgunVersion)
  val ztExec = "org.zeroturnaround" % "zt-exec" % ztExecVersion
  val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"

  val utest = "com.lihaoyi" %% "utest" % "0.6.9"
  val pprint = "com.lihaoyi" %% "pprint" % "0.5.5"
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
  val junit = "com.github.sbt" % "junit-interface" % junitVersion
  val directoryWatcher = "ch.epfl.scala" % "directory-watcher" % directoryWatcherVersion
  val difflib = "com.googlecode.java-diff-utils" % "diffutils" % difflibVersion

  import sbt.Provided

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

  val asm = "org.ow2.asm" % "asm" % asmVersion
  val asmUtil = "org.ow2.asm" % "asm-util" % asmVersion

  val libdaemonjvm = "io.github.alexarchambault.libdaemon" %% "libdaemon" % "0.0.10"

  val svmSubs = "org.scalameta" %% "svm-subs" % "20.1.0"
}
