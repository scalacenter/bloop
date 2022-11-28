package build

object Dependencies {
  val Scala210Version = "2.10.7"
  val Scala211Version = "2.11.12"
  val Scala212Version = "2.12.15"
  val Scala213Version = "2.13.8"

  val Sbt013Version = "0.13.18"
  val Sbt1Version = "1.3.3"

  val nailgunVersion = "ee3c4343"
  // Used to download the python client instead of resolving
  val nailgunCommit = "a2520c1e"

  // Keep in sync in BloopComponentCompiler
  val zincVersion = "1.7.2"

  val bspVersion = "2.1.0-M3"

  val scalazVersion = "7.2.20"
  val scalaXmlVersion = "1.2.0"
  val lmVersion = "1.1.5"
  val configDirsVersion = "26"
  val caseAppVersion = "2.0.6"
  val sourcecodeVersion = "0.3.0"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.4.4"
  val junitVersion = "0.13.3"
  val graphvizVersion = "0.2.2"
  val directoryWatcherVersion = "0.8.0+6-f651bd93"
  val mavenApiVersion = "3.6.1"
  val mavenAnnotationsVersion = "3.5"
  val mavenScalaPluginVersion = "4.5.3"
  val gradleVersion = "5.0"
  val groovyVersion = "2.5.4"
  val gradleAndroidPluginVersion = "4.2.2"
  val ipcsocketVersion = "1.0.1"
  val monixVersion = "3.2.0"
  val circeVersion = "0.9.3"
  val jsoniterVersion = "2.13.3.2"
  val circeVersion213 = "0.12.2"
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
  val jnaVersion = "5.8.0"
  val asmVersion = "7.0"
  val snailgunVersion = "0.4.0"
  val ztExecVersion = "1.11"
  val debugAdapterVersion = "3.0.5"
  val bloopConfigVersion = "1.5.5"

  import sbt.librarymanagement.syntax.stringToOrganization
  val zinc = "org.scala-sbt" %% "zinc" % zincVersion
  val bsp4s = "ch.epfl.scala" %% "bsp4s" % bspVersion
  val bsp4j = "ch.epfl.scala" % "bsp4j" % bspVersion
  val nailgun = "ch.epfl.scala" % "nailgun-server" % nailgunVersion
  val bloopConfig = "ch.epfl.scala" %% "bloop-config" % bloopConfigVersion

  val configDirectories = "dev.dirs" % "directories" % configDirsVersion
  val libraryManagement = "org.scala-sbt" %% "librarymanagement-ivy" % lmVersion
  val log4j = "org.apache.logging.log4j" % "log4j-core" % "2.17.1"
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
  val slf4jNop = "org.slf4j" % "slf4j-nop" % "1.7.2"

  val utest = "com.lihaoyi" %% "utest" % "0.8.1"
  val pprint = "com.lihaoyi" %% "pprint" % "0.8.1"
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
  val junit = "com.github.sbt" % "junit-interface" % junitVersion
  val directoryWatcher = "ch.epfl.scala" % "directory-watcher" % directoryWatcherVersion
  val difflib = "com.googlecode.java-diff-utils" % "diffutils" % difflibVersion

  import sbt.Provided
  val mavenCore = "org.apache.maven" % "maven-core" % mavenApiVersion % Provided
  val mavenPluginApi = "org.apache.maven" % "maven-plugin-api" % mavenApiVersion
  val mavenPluginAnnotations =
    "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mavenAnnotationsVersion % Provided
  val mavenScalaPlugin = "net.alchim31.maven" % "scala-maven-plugin" % mavenScalaPluginVersion

  val gradleAPI = "dev.gradleplugins" % "gradle-api" % gradleVersion % Provided
  val gradleTestKit = "dev.gradleplugins" % "gradle-test-kit" % gradleVersion % Provided
  val gradleCore = "org.gradle" % "gradle-core" % gradleVersion % Provided
  val gradleWorkers = "org.gradle" % "gradle-workers" % gradleVersion % Provided
  val gradleDependencyManagement =
    "org.gradle" % "gradle-dependency-management" % gradleVersion % Provided
  val gradleToolingApi = "org.gradle" % "gradle-tooling-api" % gradleVersion % Provided
  val groovy = "org.codehaus.groovy" % "groovy" % groovyVersion % Provided
  val gradleAndroidPlugin =
    "com.android.tools.build" % "gradle" % gradleAndroidPluginVersion % Provided

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

  val classgraph = "io.github.classgraph" % "classgraph" % "4.8.78"

  val xxHashLibrary = "net.jpountz.lz4" % "lz4" % xxHashVersion
  val zt = "org.zeroturnaround" % "zt-zip" % ztVersion

  val brave = "io.zipkin.brave" % "brave" % braveVersion
  val zipkinSender = "io.zipkin.reporter2" % "zipkin-sender-urlconnection" % zipkinSenderVersion

  val jna = "net.java.dev.jna" % "jna" % jnaVersion
  val jnaPlatform = "net.java.dev.jna" % "jna-platform" % jnaVersion
  val asm = "org.ow2.asm" % "asm" % asmVersion
  val asmUtil = "org.ow2.asm" % "asm-util" % asmVersion
}
