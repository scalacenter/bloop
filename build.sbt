import build.BuildImplementation.BuildDefaults
import build.BuildImplementation.jvmOptions
import scala.util.Properties

inThisBuild(
  List(
    organization := "io.github.alexarchambault.bleep",
    homepage := Some(url("https://github.com/alexarchambault/bleep")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "alexarchambault",
        "Alex Archambault",
        "",
        url("https://github.com/alexarchambault")
      )
    ),
    sonatypeCredentialHost := "s01.oss.sonatype.org"
  )
)

lazy val sonatypeSetting = Def.settings(
  sonatypeProfileName := "io.github.alexarchambault"
)

(ThisBuild / dynverSeparator) := "-"

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"

// Add hook for scalafmt validation
Global / onLoad ~= { old =>
  if (!scala.util.Properties.isWin) {
    import java.nio.file._
    val prePush = Paths.get(".git", "hooks", "pre-push")
    Files.createDirectories(prePush.getParent)
    Files.write(
      prePush,
      """#!/bin/sh
        |set -eux
        |bin/scalafmt --diff --diff-branch main
        |git diff --exit-code
        |""".stripMargin.getBytes()
    )
    prePush.toFile.setExecutable(true)
  }
  old
}

val scalafixSettings: Seq[Setting[_]] = Seq(
  scalacOptions ++= {
    if (scalaVersion.value.startsWith("2.11")) Seq("-Ywarn-unused-import")
    else if (scalaVersion.value.startsWith("2.12")) Seq("-Ywarn-unused", "-Xlint:unused")
    else if (scalaVersion.value.startsWith("2.13")) Seq("-Wunused")
    else Seq.empty
  },
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision
)

lazy val shared = project
  .settings(
    sonatypeSetting,
    name := "bloop-shared",
    scalafixSettings,
    libraryDependencies ++= Seq(
      Dependencies.jsoniterCore,
      Dependencies.jsoniterMacros,
      Dependencies.bsp4s excludeAll ExclusionRule(
        organization = "com.github.plokhotnyuk.jsoniter-scala"
      ),
      Dependencies.coursierInterface,
      Dependencies.zinc,
      Dependencies.log4j,
      Dependencies.xxHashLibrary,
      Dependencies.sbtTestInterface,
      Dependencies.sbtTestAgent
    )
  )

import build.Dependencies

lazy val backend = project
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(shared)
  .settings(
    sonatypeSetting,
    name := "bloop-backend",
    scalafixSettings,
    testSettings ++ testSuiteSettings,
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := Seq[BuildInfoKey](
      Keys.scalaVersion,
      Keys.scalaOrganization
    ),
    buildInfoObject := "BloopScalaInfo",
    libraryDependencies ++= List(
      Dependencies.nailgun,
      Dependencies.scalazCore,
      Dependencies.coursierInterface,
      Dependencies.libraryManagement,
      Dependencies.sourcecode,
      Dependencies.monix,
      Dependencies.directoryWatcher,
      Dependencies.zt,
      Dependencies.brave,
      Dependencies.zipkinSender,
      Dependencies.pprint,
      Dependencies.difflib,
      Dependencies.asm,
      Dependencies.asmUtil
    )
  )

val testResourceSettings = {
  // FIXME: Shared resource directory is ignored, see https://github.com/portable-scala/sbt-crossproject/issues/74
  Seq(Test).flatMap(inConfig(_) {
    unmanagedResourceDirectories ++= {
      unmanagedSourceDirectories.value
        .map(src => (src / ".." / "resources").getCanonicalFile)
        .filterNot(unmanagedResourceDirectories.value.contains)
        .distinct
    }
  })
}

lazy val tmpDirSettings = Def.settings(
  javaOptions in Test += {
    val tmpDir = (baseDirectory in ThisBuild).value / "target" / "tests-tmp"
    s"-Dbloop.tests.tmp-dir=$tmpDir"
  }
)

// For the moment, the dependency is fixed
lazy val frontend: Project = project
  .dependsOn(
    backend,
    backend % "test->test"
  )
  .enablePlugins(BuildInfoPlugin)
  .configs(IntegrationTest)
  .settings(
    sonatypeSetting,
    BuildDefaults.frontendTestBuildSettings,
    name := "bloop-frontend",
    bloopName := "bloop",
    (Compile / run / mainClass) := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := List[BuildInfoKey](
      Keys.organization,
      build.BuildKeys.bloopName,
      Keys.version,
      Keys.scalaVersion,
      nailgunClientLocation,
      "zincVersion" -> Dependencies.zincVersion,
      "bspVersion" -> Dependencies.bspVersion,
      "nativeBridge04" -> (nativeBridge04Name + "_" + Keys.scalaBinaryVersion.value),
      "jsBridge1" -> (jsBridge1Name + "_" + Keys.scalaBinaryVersion.value),
      "snailgunVersion" -> Dependencies.snailgunVersion
    ),
    (run / javaOptions) ++= jvmOptions,
    (Test / javaOptions) ++= jvmOptions,
    tmpDirSettings,
    (IntegrationTest / javaOptions) ++= jvmOptions,
    (run / fork) := true,
    (Test / fork) := true,
    (IntegrationTest / run / fork) := true,
    (test / parallelExecution) := false,
    libraryDependencies ++= List(
      Dependencies.jsoniterMacros % Provided,
      Dependencies.caseApp,
      Dependencies.scalaDebugAdapter,
      Dependencies.bloopConfig,
      Dependencies.libdaemonjvm,
      Dependencies.logback
    )
  )
  .disablePlugins(ScalafixPlugin)

val jsBridge1Name = "bloop-js-bridge-1"
lazy val jsBridge1 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scalajs-1")
  .settings(
    sonatypeSetting,
    name := jsBridge1Name,
    testSettings,
    libraryDependencies ++= List(
      Dependencies.scalaJsLinker1,
      Dependencies.scalaJsLogging1,
      Dependencies.scalaJsEnvs1,
      Dependencies.scalaJsEnvNode1,
      Dependencies.scalaJsEnvJsdomNode1,
      Dependencies.scalaJsSbtTestAdapter1
    )
  )

val nativeBridge04Name = "bloop-native-bridge-0-4"
lazy val nativeBridge04 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scala-native-0.4")
  .disablePlugins(ScalafixPlugin)
  .settings(
    sonatypeSetting,
    name := nativeBridge04Name,
    testSettings,
    libraryDependencies += Dependencies.scalaNativeTools04,
    (Test / javaOptions) ++= jvmOptions,
    (Test / fork) := true
  )

(publish / skip) := true
sonatypeSetting
