package build

object Dependencies {
  val Scala210Version = "2.10.7"
  val Scala211Version = "2.11.12"
  val Scala212Version = "2.12.8"

  val Sbt013Version = "0.13.18"
  val Sbt1Version = "1.3.3"

  val nailgunVersion = "ee3c4343"
  // Used to download the python client instead of resolving
  val nailgunCommit = "a2520c1e"

  val zincVersion = "1.3.0-M4+32-b1accb96"
  val bspVersion = "2.0.0-M4+10-61e61e87"
  val javaDebugVersion = "0.21.0+1-7f1080f1"

  val scalazVersion = "7.2.20"
  val coursierVersion = "2.0.0-RC3-4"
  val scalaXmlVersion = "1.2.0"
  val lmVersion = "1.0.0"
  val configDirsVersion = "10"
  val caseAppVersion = "1.2.0-faster-compile-time"
  val sourcecodeVersion = "0.1.4"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.2.8"
  val junitVersion = "0.11"
  val junitSystemRulesVersion = "1.19.0"
  val graphvizVersion = "0.2.2"
  val directoryWatcherVersion = "0.8.0+6-f651bd93"
  val mavenApiVersion = "3.6.1"
  val mavenAnnotationsVersion = "3.5"
  val mavenScalaPluginVersion = "3.2.2"
  val gradleVersion = "3.5"
  val groovyVersion = "2.5.0"
  val ipcsocketVersion = "1.0.1"
  val monixVersion = "2.3.3"
  val circeVersion = "0.9.3"
  val jsoniterVersion = "2.0.4"
  val circeVersion213 = "0.12.2"
  val nuprocessVersion = "1.2.4"
  val shapelessVersion = "2.3.3-lower-priority-coproduct"
  val scalaNative03Version = "0.3.9"
  val scalaNative04Version = "0.4.0-M2"
  val scalaJs06Version = "0.6.28"
  val scalaJs10Version = "1.0.0-M5"
  val millVersion = "0.3.6"
  val xxHashVersion = "1.3.0"
  val ztVersion = "1.13"
  val difflibVersion = "1.3.0"
  val braveVersion = "5.6.1"
  val zipkinSenderVersion = "2.7.15"
  val jnaVersion = "4.5.0"
  val asmVersion = "7.0"
  val snailgunVersion = "0.3.1"
  val ztExecVersion = "1.11"

  import sbt.librarymanagement.syntax.stringToOrganization
  val zinc = "ch.epfl.scala" %% "zinc" % zincVersion
  val bsp4s = "ch.epfl.scala" %% "bsp4s" % bspVersion
  val bsp4j = "ch.epfl.scala" % "bsp4j" % bspVersion
  val nailgun = "ch.epfl.scala" % "nailgun-server" % nailgunVersion
  val javaDebug = "ch.epfl.scala" % "com-microsoft-java-debug-core" % javaDebugVersion

  val configDirectories = "io.github.soc" % "directories" % configDirsVersion
  val libraryManagement = "org.scala-sbt" %% "librarymanagement-ivy" % lmVersion
  val scalazCore = "org.scalaz" %% "scalaz-core" % scalazVersion
  val scalazConcurrent = "org.scalaz" %% "scalaz-concurrent" % scalazVersion
  val coursier = "io.get-coursier" %% "coursier" % coursierVersion
  val coursierCache = "io.get-coursier" %% "coursier-cache" % coursierVersion
  val coursierScalaz = "io.get-coursier" %% "coursier-scalaz-interop" % coursierVersion
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % scalaXmlVersion
  val shapeless = "ch.epfl.scala" %% "shapeless" % shapelessVersion
  val caseApp = "ch.epfl.scala" %% "case-app" % caseAppVersion
  val sourcecode = "com.lihaoyi" %% "sourcecode" % sourcecodeVersion
  val sbtTestInterface = "org.scala-sbt" % "test-interface" % sbtTestInterfaceVersion
  val sbtTestAgent = "org.scala-sbt" % "test-agent" % sbtTestAgentVersion
  val snailgun = ("me.vican.jorge" %% "snailgun-cli" % snailgunVersion)
  val ztExec = "org.zeroturnaround" % "zt-exec" % ztExecVersion
  val slf4jNop = "org.slf4j" % "slf4j-nop" % "1.7.2"

  val scalatest = "org.scalatest" % "scalatest_2.12" % "3.0.5"
  val utest = "com.lihaoyi" %% "utest" % "0.6.6"
  val pprint = "com.lihaoyi" %% "pprint" % "0.5.3"
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
  val junit = "com.novocode" % "junit-interface" % junitVersion
  val graphviz = "guru.nidi" % "graphviz-java" % graphvizVersion
  val directoryWatcher = "ch.epfl.scala" % "directory-watcher" % directoryWatcherVersion
  val difflib = "com.googlecode.java-diff-utils" % "diffutils" % difflibVersion
  val junitSystemRules = "com.github.stefanbirkner" % "system-rules" % junitSystemRulesVersion

  import sbt.Provided
  val mavenCore = "org.apache.maven" % "maven-core" % mavenApiVersion % Provided
  val mavenPluginApi = "org.apache.maven" % "maven-plugin-api" % mavenApiVersion
  val mavenInvoker = "org.apache.maven.shared" % "maven-invoker" % "3.0.1"
  val mavenPluginAnnotations = "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mavenAnnotationsVersion % Provided
  val mavenScalaPlugin = "net.alchim31.maven" % "scala-maven-plugin" % mavenScalaPluginVersion

  val gradleCore = "org.gradle" % "gradle-core" % gradleVersion % Provided
  val gradleWorkers = "org.gradle" % "gradle-workers" % gradleVersion % Provided
  val gradleDependencyManagement = "org.gradle" % "gradle-dependency-management" % gradleVersion % Provided
  val gradleToolingApi = "org.gradle" % "gradle-tooling-api" % gradleVersion % Provided
  val groovy = "org.codehaus.groovy" % "groovy" % groovyVersion % Provided

  val monix = "io.monix" %% "monix" % monixVersion
  val jsoniterCore = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion
  val jsoniterMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion
  val circeParser = "io.circe" %% "circe-parser" % circeVersion
  val circeCore = "io.circe" %% "circe-core" % circeVersion
  val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  val nuprocess = "com.zaxxer" % "nuprocess" % nuprocessVersion

  val scalaNativeTools03 = "org.scala-native" %% "tools" % scalaNative03Version % Provided
  val scalaNativeTools04 = "org.scala-native" %% "tools" % scalaNative04Version % Provided
  val scalaJsTools06 = "org.scala-js" %% "scalajs-tools" % scalaJs06Version % Provided
  val scalaJsSbtTestAdapter06 = "org.scala-js" %% "scalajs-sbt-test-adapter" % scalaJs06Version % Provided
  val scalaJsEnvs06 = "org.scala-js" %% "scalajs-js-envs" % scalaJs06Version % Provided

  val scalaJsLinker10 = "org.scala-js" %% "scalajs-linker" % scalaJs10Version % Provided
  val scalaJsIO10 = "org.scala-js" %% "scalajs-io" % scalaJs10Version % Provided
  val scalaJsEnvs10 = "org.scala-js" %% "scalajs-js-envs" % scalaJs10Version % Provided
  val scalaJsEnvNode10 = "org.scala-js" %% "scalajs-env-nodejs" % scalaJs10Version % Provided
  val scalaJsEnvJsdomNode10 = "org.scala-js" %% "scalajs-env-jsdom-nodejs" % scalaJs10Version % Provided
  val scalaJsSbtTestAdapter10 = "org.scala-js" %% "scalajs-sbt-test-adapter" % scalaJs10Version % Provided
  val scalaJsLogging10 = "org.scala-js" %% "scalajs-logging" % scalaJs10Version % Provided

  val mill = "com.lihaoyi" %% "mill-scalalib" % millVersion % Provided
  val xxHashLibrary = "net.jpountz.lz4" % "lz4" % xxHashVersion
  val zt = "org.zeroturnaround" % "zt-zip" % ztVersion

  val brave = "io.zipkin.brave" % "brave" % braveVersion
  val zipkinSender = "io.zipkin.reporter2" % "zipkin-sender-urlconnection" % zipkinSenderVersion
  val zipkinOkHttp = "io.zipkin.reporter2" % "zipkin-sender-okhttp3" % zipkinSenderVersion

  val jna = "net.java.dev.jna" % "jna" % jnaVersion
  val jnaPlatform = "net.java.dev.jna" % "jna-platform" % jnaVersion
  val asm = "org.ow2.asm" % "asm" % asmVersion
  val asmUtil = "org.ow2.asm" % "asm-util" % asmVersion
}
