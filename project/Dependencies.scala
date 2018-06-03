package build

object Dependencies {
  val nailgunVersion = "60c2d130"
  val zincVersion = "1.1.7+62-0f4ad9d5"
  val bspVersion = "ce87199c"
  val scalazVersion = "7.2.20"
  val coursierVersion = "1.1.0-M3"
  val lmVersion = "1.0.0"
  val configDirsVersion = "10"
  val caseAppVersion = "1.2.0-faster-compile-time"
  val sourcecodeVersion = "0.1.4"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.0.4"
  val junitVersion = "0.11"
  val graphvizVersion = "0.2.2"
  val directoryWatcherVersion = "0.5.2-a1c0e21c"
  val mavenApiVersion = "3.5.2"
  val mavenAnnotationsVersion = "3.5"
  val mavenScalaPluginVersion = "3.2.2"
  val ipcsocketVersion = "1.0.0"
  val monixVersion = "2.3.3"
  val circeVersion = "0.9.3"
  val nuprocessVersion = "1.2.3"
  val shapelessVersion = "2.3.3-lower-priority-coproduct"
  val scalaNativeVersion = "0.3.7"
  val scalaJs06Version = "0.6.23"
  val scalaJs10Version = "1.0.0-M2"
  val millVersion = "0.2.3"

  import sbt.librarymanagement.syntax.stringToOrganization
  val zinc = "ch.epfl.scala" %% "zinc" % zincVersion
  val bsp = "ch.epfl.scala" %% "bsp" % bspVersion
  val nailgun = "ch.epfl.scala" % "nailgun-server" % nailgunVersion

  val configDirectories = "io.github.soc" % "directories" % configDirsVersion
  val libraryManagement = "org.scala-sbt" %% "librarymanagement-ivy" % lmVersion
  val scalazCore = "org.scalaz" %% "scalaz-core" % scalazVersion
  val scalazConcurrent = "org.scalaz" %% "scalaz-concurrent" % scalazVersion
  val coursier = "io.get-coursier" %% "coursier" % coursierVersion
  val coursierCache = "io.get-coursier" %% "coursier-cache" % coursierVersion
  val coursierScalaz = "io.get-coursier" %% "coursier-scalaz-interop" % coursierVersion
  val shapeless = "ch.epfl.scala" %% "shapeless" % shapelessVersion
  val caseApp = "ch.epfl.scala" %% "case-app" % caseAppVersion
  val sourcecode = "com.lihaoyi" %% "sourcecode" % sourcecodeVersion
  val sbtTestInterface = "org.scala-sbt" % "test-interface" % sbtTestInterfaceVersion
  val sbtTestAgent = "org.scala-sbt" % "test-agent" % sbtTestAgentVersion

  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
  val junit = "com.novocode" % "junit-interface" % junitVersion
  val graphviz = "guru.nidi" % "graphviz-java" % graphvizVersion
  val directoryWatcher = "me.vican.jorge" %% "directory-watcher-better-files" % directoryWatcherVersion

  import sbt.Provided
  val mavenCore = "org.apache.maven" % "maven-core" % mavenApiVersion % Provided
  val mavenPluginApi = "org.apache.maven" % "maven-plugin-api" % mavenApiVersion
  val mavenPluginAnnotations = "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mavenAnnotationsVersion % Provided
  val mavenScalaPlugin = "net.alchim31.maven" % "scala-maven-plugin" % mavenScalaPluginVersion

  val ipcsocket = "org.scala-sbt.ipcsocket" % "ipcsocket" % ipcsocketVersion
  val monix = "io.monix" %% "monix" % monixVersion

  val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"
  val circeParser = "io.circe" %% "circe-parser" % circeVersion
  val circeCore = "io.circe" %% "circe-core" % circeVersion
  val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  val nuprocess = "com.zaxxer" % "nuprocess" % nuprocessVersion

  val scalaNativeTools = "org.scala-native" %% "tools" % scalaNativeVersion
  val scalaJsTools06 = "org.scala-js" %% "scalajs-tools" % scalaJs06Version
  val scalaJsTools10 = "org.scala-js" %% "scalajs-tools" % scalaJs10Version
  val mill = "com.lihaoyi" %% "mill-scalalib"	% millVersion
}
