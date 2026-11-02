package build

import sbt.librarymanagement.syntax.stringToOrganization
import sbt.librarymanagement.CrossVersion
import sbt.ExclusionRule
import sbt.ModuleID
import sbt.Provided

object Dependencies {
  val Scala211Version = "2.11.12"
  val Scala212Version = "2.12.21"
  val Scala213Version = "2.13.18"
  // 3.3 LTS for the server, bloop-rifle, the CLI, and the native image. 3.8 is the sbt 2 plugin.
  val Scala33Version = "3.3.9"
  val Scala3Version = "3.8.4"

  val SbtVersion = "1.9.0"

  // Keep in sync in BloopComponentCompiler
  val zincVersion = "1.13.0"

  val bspVersion = "2.1.1"

  val scalazVersion = "7.3.9"
  val lmVersion = "1.12.3"
  val configDirsVersion = "26"
  val caseAppVersion = "2.1.0-M30"
  val coursierVersion = "2.1.26"
  val sourcecodeVersion = "0.4.4"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.13.1"
  val junitVersion = "0.13.3"
  val jupiterInterfaceVersion = "0.19.0"
  val directoryWatcherVersion = "0.8.0+6-f651bd93"
  val monixVersion = "3.5.2"
  val jsoniterVersion = "2.13.3.2"
  val scalaNative04Version = "0.4.17"
  val scalaNative05Version = "0.5.12"
  val scalaJs06Version = "0.6.33"
  val scalaJs1Version = "1.22.0"
  val scalaJsEnvsVersion = "1.6.0"
  val xxHashVersion = "1.3.0"
  val ztVersion = "1.18.3"
  val difflibVersion = "1.3.0"
  val braveVersion = "5.18.1"
  val zipkinSenderVersion = "2.17.2"
  val jnaVersion = "5.19.1"
  val asmVersion = "9.11.1"
  val ztExecVersion = "1.13.0"
  val debugAdapterVersion = "4.2.13"
  val bloopConfigVersion = "2.3.3"
  val semanticdbVersion = "4.14.0"
  val millVersion = "0.12.7"

  /**
   * Uses the Scala 2.13 artifact of a library that is not published for Scala 3. Its Scala
   * dependencies that the server also uses as `_3` artifacts are excluded, so that only the `_3`
   * ones end up on the classpath.
   */
  def for3Use213(module: ModuleID): ModuleID =
    module
      .cross(CrossVersion.for3Use2_13)
      .excludeAll(
        ExclusionRule(organization = "io.monix"),
        ExclusionRule(organization = "org.typelevel"),
        ExclusionRule(organization = "com.github.plokhotnyuk.jsoniter-scala")
      )
      .exclude("com.lihaoyi", "sourcecode_2.13")
      .exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
      .exclude("org.scala-lang.modules", "scala-parallel-collections_2.13")

  val zinc = for3Use213("org.scala-sbt" %% "zinc" % zincVersion)
  val bsp4s = for3Use213("ch.epfl.scala" %% "bsp4s" % bspVersion)
  val bsp4j = "ch.epfl.scala" % "bsp4j" % bspVersion
  val jsonrpc4s = for3Use213("io.github.alexarchambault.bleep" %% "jsonrpc4s" % "0.1.1")

  val nailgun = "io.github.alexarchambault.bleep" % "nailgun-server" % "1.0.7"
  val bloopConfig = "ch.epfl.scala" %% "bloop-config" % bloopConfigVersion

  val libraryManagement = for3Use213("org.scala-sbt" %% "librarymanagement-ivy" % lmVersion)
  // Backs the Scala instance used to compile Java-only projects
  val scalaCompiler = "org.scala-lang" % "scala-compiler" % Scala213Version
  val scalaParallelCollections =
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0"
  val log4j = "org.apache.logging.log4j" % "log4j-core" % "2.26.1"
  val scalazCore = "org.scalaz" %% "scalaz-core" % scalazVersion
  val coursierInterface = "io.get-coursier" % "interface" % "1.0.30"
  val configDirectories = "io.get-coursier.util" % "directories-jni" % "0.1.2"
  // directories-jni 0.1.2 pulls windows-jni-utils 0.3.3, which only ships an x64 DLL;
  // 0.4.0 adds Windows ARM64
  val windowsJniUtils = "io.get-coursier.jniutils" % "windows-jni-utils" % "0.4.0"
  val coursierInterfaceSubs = "io.get-coursier" % "interface-svm-subs" % "1.0.19"
  val svm = "org.graalvm.nativeimage" % "svm" % "25.0.5.0.3"
  val coursier = "io.get-coursier" %% "coursier" % coursierVersion
  val coursierJvm = "io.get-coursier" %% "coursier-jvm" % coursierVersion
  val dependency = "io.get-coursier" %% "dependency" % "0.3.3"
  val directoriesJni = "io.get-coursier.util" % "directories-jni" % "0.1.4"
  val scalaCollectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0"
  val caseApp = "com.github.alexarchambault" %% "case-app" % caseAppVersion
  val sourcecode = "com.lihaoyi" %% "sourcecode" % sourcecodeVersion
  val sbtTestInterface = "org.scala-sbt" % "test-interface" % sbtTestInterfaceVersion
  val sbtTestAgent = "org.scala-sbt" % "test-agent" % sbtTestAgentVersion
  val ztExec = "org.zeroturnaround" % "zt-exec" % ztExecVersion
  val logback = "ch.qos.logback" % "logback-classic" % "1.6.6"
  val libdaemonjvm = "ch.epfl.scala" %% "libdaemon" % "0.0.12"
  val scopt = "com.github.scopt" % "scopt_2.12" % "4.0.0-RC2"

  val utest = "com.lihaoyi" %% "utest" % "0.9.5"
  def munit = "org.scalameta" %% "munit" % "1.3.6"
  def expecty = "com.eed3si9n.expecty" %% "expecty" % "0.17.1"
  val pprint = "com.lihaoyi" %% "pprint" % "0.9.6"
  val oslib = "com.lihaoyi" %% "os-lib" % "0.11.10"
  val junit = "com.github.sbt" % "junit-interface" % junitVersion
  val jupiterInterface = "com.github.sbt.junit" % "jupiter-interface" % jupiterInterfaceVersion
  val directoryWatcher = "ch.epfl.scala" % "directory-watcher" % directoryWatcherVersion
  val difflib = "com.googlecode.java-diff-utils" % "diffutils" % difflibVersion

  val monix = "io.monix" %% "monix" % monixVersion
  val jsoniterCore =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion
  // jsonrpc4s uses classes generated by the Scala 2 specialization of jsoniter-scala-core, which
  // the Scala 3 artifact doesn't contain, so the Scala 3 server uses the 2.13 one. The version is
  // the one bloop-config is compiled against.
  val jsoniterCore213 =
    ("com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.13.5.2")
      .cross(CrossVersion.for3Use2_13)
  val jsoniterCore3 = "com.github.plokhotnyuk.jsoniter-scala" % "jsoniter-scala-core_3"
  val jsoniterMacros =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion
  val scalaDebugAdapter = ("ch.epfl.scala" %% "scala-debug-adapter" % debugAdapterVersion)
    .exclude("com.lihaoyi", "sourcecode_2.13")

  // The toolchains are resolved at runtime for the platform version of the user's project, and
  // older versions are not published for Scala 3, so the bridges always use the 2.13 artifacts.
  private def toolchain(module: ModuleID): ModuleID =
    for3Use213(module) % Provided

  val scalaNativeTools04 = toolchain("org.scala-native" %% "tools" % scalaNative04Version)
  val scalaNativeTools05 = toolchain("org.scala-native" %% "tools" % scalaNative05Version)
  val scalaJsTools06 = toolchain("org.scala-js" %% "scalajs-tools" % scalaJs06Version)
  val scalaJsSbtTestAdapter06 =
    toolchain("org.scala-js" %% "scalajs-sbt-test-adapter" % scalaJs06Version)
  val scalaJsEnvs06 = toolchain("org.scala-js" %% "scalajs-js-envs" % scalaJs06Version)

  val scalaJsLinker1 = toolchain("org.scala-js" %% "scalajs-linker" % scalaJs1Version)
  val scalaJsEnvs1 = toolchain("org.scala-js" %% "scalajs-js-envs" % scalaJsEnvsVersion)
  val scalaJsEnvNode1 = toolchain("org.scala-js" %% "scalajs-env-nodejs" % scalaJsEnvsVersion)
  val scalaJsEnvJsdomNode1 = toolchain("org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.1")
  val scalaJsSbtTestAdapter1 =
    toolchain("org.scala-js" %% "scalajs-sbt-test-adapter" % scalaJs1Version)
  val scalaJsLogging1 = toolchain("org.scala-js" %% "scalajs-logging" % "1.2.0")
  // Not used by the build, only kept so that the version used in tests gets updated
  val semanticdb =
    "org.scalameta" % s"semanticdb-scalac_$Scala213Version" % semanticdbVersion

  val xxHashLibrary = "net.jpountz.lz4" % "lz4" % xxHashVersion
  val zt = "org.zeroturnaround" % "zt-zip" % ztVersion

  val brave = "io.zipkin.brave" % "brave" % braveVersion
  val zipkinSender = "io.zipkin.reporter2" % "zipkin-sender-urlconnection" % zipkinSenderVersion

  val jna = "net.java.dev.jna" % "jna" % jnaVersion
  val jnaPlatform = "net.java.dev.jna" % "jna-platform" % jnaVersion
  val asm = "org.ow2.asm" % "asm" % asmVersion
  val asmUtil = "org.ow2.asm" % "asm-util" % asmVersion
}
