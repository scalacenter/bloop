package build

object Dependencies {
  val nailgunVersion = "615737a9"
  val zincVersion = "1.1.1+49-1c290cbb"
  val bspVersion = "1ca97e62"
  val coursierVersion = "1.0.0-RC8"
  val lmVersion = "1.0.0"
  val configDirsVersion = "5"
  val caseAppVersion = "1.2.0"
  val sourcecodeVersion = "0.1.4"
  val sbtTestInterfaceVersion = "1.0"
  val sbtTestAgentVersion = "1.0.4"
  val junitVersion = "0.11"
  val graphvizVersion = "0.2.2"
  val directoryWatcherVersion = "0.4.0"
  val mavenApiVersion = "3.5.2"
  val mavenAnnotationsVersion = "3.5"
  val mavenScalaPluginVersion = "3.2.2"
  val ipcsocketVersion = "1.0.0"
  val monixVersion = "2.3.3"
  val metaconfigVersion = "0.6.0"
  val circeVersion = "0.9.3"

  import sbt.librarymanagement.syntax.stringToOrganization
  val zinc = "ch.epfl.scala" %% "zinc" % zincVersion
  val bsp = "ch.epfl.scala" %% "bsp" % bspVersion
  val nailgun = "ch.epfl.scala" % "nailgun-server" % nailgunVersion

  val configDirectories = "io.github.soc" % "directories" % configDirsVersion
  val libraryManagement = "org.scala-sbt" %% "librarymanagement-ivy" % lmVersion
  val coursier = "io.get-coursier" %% "coursier" % coursierVersion
  val coursierCache = "io.get-coursier" %% "coursier-cache" % coursierVersion
  val caseApp = "com.github.alexarchambault" %% "case-app" % caseAppVersion
  val sourcecode = "com.lihaoyi" %% "sourcecode" % sourcecodeVersion
  val sbtTestInterface = "org.scala-sbt" % "test-interface" % sbtTestInterfaceVersion
  val sbtTestAgent = "org.scala-sbt" % "test-agent" % sbtTestAgentVersion

  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
  val junit = "com.novocode" % "junit-interface" % junitVersion
  val graphviz = "guru.nidi" % "graphviz-java" % graphvizVersion
  val directoryWatcher = "io.methvin" %% "directory-watcher-better-files" % directoryWatcherVersion

  import sbt.Provided
  val mavenCore = "org.apache.maven" % "maven-core" % mavenApiVersion % Provided
  val mavenPluginApi = "org.apache.maven" % "maven-plugin-api" % mavenApiVersion
  val mavenPluginAnnotations = "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mavenAnnotationsVersion % Provided
  val mavenScalaPlugin = "net.alchim31.maven" % "scala-maven-plugin" % mavenScalaPluginVersion

  val ipcsocket = "org.scala-sbt.ipcsocket" % "ipcsocket" % ipcsocketVersion
  val monix = "io.monix" %% "monix" % monixVersion

  val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"
  val typesafeConfig = "com.typesafe" % "config" % "1.3.2"
  val metaconfigCore = "com.geirsson" %% "metaconfig-core" % metaconfigVersion
  val metaconfigConfig = "com.geirsson" %% "metaconfig-typesafe-config" % metaconfigVersion
  val metaconfigDocs = "com.geirsson" %% "metaconfig-docs" % metaconfigVersion
  val circeCore = "io.circe" %% "circe-core" % circeVersion
  val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
}
