package build

import java.io.File

import ch.epfl.scala.sbt.release.Feedback
import com.typesafe.sbt.SbtPgp.{autoImport => Pgp}
import sbt.{AutoPlugin, Command, Def, Keys, PluginTrigger, Plugins, Task, ThisBuild}
import sbt.io.{AllPassFilter, IO}
import sbt.io.syntax.fileToRichFile
import sbt.librarymanagement.syntax.stringToOrganization
import sbtdynver.GitDescribeOutput

object BuildPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  import com.typesafe.sbt.SbtPgp
  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin
  import com.lucidchart.sbt.scalafmt.ScalafmtCorePlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    JvmPlugin && ScalafmtCorePlugin && ReleaseEarlyPlugin && SbtPgp
  val autoImport = BuildKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    BuildImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    BuildImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    BuildImplementation.projectSettings
}

object BuildKeys {
  import sbt.{Reference, RootProject, ProjectRef, BuildRef, file, uri}
  def inProject(ref: Reference)(ss: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] =
    sbt.inScope(sbt.ThisScope.in(project = ref))(ss)

  def inProjectRefs(refs: Seq[Reference])(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    refs.flatMap(inProject(_)(ss))

  def inCompileAndTest(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    Seq(sbt.Compile, sbt.Test).flatMap(sbt.inConfig(_)(ss))

  // Use absolute paths so that references work even if `ThisBuild` changes
  final val AbsolutePath = file(".").getCanonicalFile.getAbsolutePath

  private val isCiDisabled = sys.env.get("CI").isEmpty
  def createScalaCenterProject(name: String, f: File): RootProject = {
    if (isCiDisabled) RootProject(f)
    else {
      val headSha = new com.typesafe.sbt.git.DefaultReadableGit(f).withGit(_.headCommitSha)
      headSha match {
        case Some(commit) => RootProject(uri(s"git://github.com/scalacenter/${name}.git#$commit"))
        case None => sys.error(s"The 'HEAD' sha of '${f}' could not be retrieved.")
      }
    }
  }

  final val NailgunProject = createScalaCenterProject("nailgun", file(s"$AbsolutePath/nailgun"))
  final val NailgunBuild = BuildRef(NailgunProject.build)
  final val Nailgun = ProjectRef(NailgunProject.build, "nailgun")
  final val NailgunServer = ProjectRef(NailgunProject.build, "nailgun-server")
  final val NailgunExamples = ProjectRef(NailgunProject.build, "nailgun-examples")

  final val BenchmarkBridgeProject =
    createScalaCenterProject("compiler-benchmark", file(s"$AbsolutePath/benchmark-bridge"))
  final val BenchmarkBridgeBuild = BuildRef(BenchmarkBridgeProject.build)
  final val BenchmarkBridgeCompilation = ProjectRef(BenchmarkBridgeProject.build, "compilation")

  import sbt.{Test, TestFrameworks, Tests}
  val buildBase = Keys.baseDirectory in ThisBuild
  val integrationTestsLocation = Def.settingKey[sbt.File]("Where to find the integration tests")
  val scriptedAddSbtBloop = Def.taskKey[Unit]("Add sbt-bloop to the test projects")
  val updateHomebrewFormula = Def.taskKey[Unit]("Update Homebrew formula")
  val testSettings: Seq[Def.Setting[_]] = List(
    integrationTestsLocation := buildBase.value / "integration-tests" / "integration-projects",
    Keys.testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    Keys.libraryDependencies ++= List(
      Dependencies.junit % Test
    ),
  )

  import ohnosequences.sbt.GithubRelease.{keys => GHReleaseKeys}
  val releaseSettings = Seq(
    GHReleaseKeys.ghreleaseNotes := { tagName =>
      IO.read(buildBase.value / "notes" / s"$tagName.md")
    },
    GHReleaseKeys.ghreleaseRepoOrg := "scalacenter",
    GHReleaseKeys.ghreleaseRepoName := "bloop",
    GHReleaseKeys.ghreleaseAssets += ReleaseUtils.versionedInstallScript.value,
    updateHomebrewFormula := ReleaseUtils.updateHomebrewFormula.value
  )

  import sbtbuildinfo.BuildInfoKey
  final val BloopInfoKeys = {
    val zincKey = BuildInfoKey.constant("zincVersion" -> Dependencies.zincVersion)
    val developersKey = BuildInfoKey.map(Keys.developers) {
      case (k, devs) => k -> devs.map(_.name)
    }
    val commonKeys = List[BuildInfoKey](Keys.name,
                                        Keys.version,
                                        Keys.scalaVersion,
                                        Keys.sbtVersion,
                                        integrationTestsLocation)
    commonKeys ++ List(zincKey, developersKey)
  }

  import sbtassembly.{AssemblyKeys, MergeStrategy}
  val assemblySettings: Seq[Def.Setting[_]] = List(
    Keys.mainClass in AssemblyKeys.assembly := Some("bloop.Bloop"),
    Keys.test in AssemblyKeys.assembly := {},
    AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly := {
      case "LICENSE.md" => MergeStrategy.first
      case "NOTICE.md" => MergeStrategy.first
      case x =>
        val oldStrategy = (AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly).value
        oldStrategy(x)
    }
  )

  import sbtbuildinfo.BuildInfoKeys
  def benchmarksSettings(project: Reference): Seq[Def.Setting[_]] = List(
    Keys.skip in Keys.publish := true,
    BuildInfoKeys.buildInfoKeys := Seq[BuildInfoKey](Keys.resourceDirectory in sbt.Test in project),
    BuildInfoKeys.buildInfoPackage := "bloop.benchmarks",
    Keys.javaOptions ++= {
      def refOf(version: String) = {
        val HasSha = """(?:.+?)-([0-9a-f]{8})(?:\+\d{8}-\d{4})?""".r
        version match {
          case HasSha(sha) => sha
          case _ => version
        }
      }
      List(
        "-Dsbt.launcher=" + (sys
          .props("java.class.path")
          .split(java.io.File.pathSeparatorChar)
          .find(_.contains("sbt-launch"))
          .getOrElse("")),
        "-DbloopVersion=" + Keys.version.in(project).value,
        "-DbloopRef=" + refOf(Keys.version.in(project).value),
        "-Dbloop.jar=" + AssemblyKeys.assembly.in(project).value,
        "-Dgit.localdir=" + buildBase.value.getAbsolutePath
      )
    }
  )

  import com.typesafe.sbt.site.SitePlugin.{autoImport => SiteKeys}
  import com.typesafe.sbt.site.hugo.HugoPlugin.{autoImport => HugoKeys}
  import com.typesafe.sbt.sbtghpages.GhpagesPlugin.{autoImport => GhpagesKeys}
  import com.typesafe.sbt.SbtGit.GitKeys
  import sbt.io.GlobFilter
  val websiteSettings: Seq[Def.Setting[_]] = Seq(
    Keys.sourceDirectory in HugoKeys.Hugo := Keys.baseDirectory.value,
    Keys.includeFilter in HugoKeys.Hugo := (Keys.includeFilter in SiteKeys.makeSite).value || GlobFilter(
      "*.svg"),
    HugoKeys.baseURL in HugoKeys.Hugo := sbt.uri("https://scalacenter.github.com/bloop"),
    GitKeys.gitRemoteRepo := "git@github.com:scalacenter/bloop.git",
    GhpagesKeys.ghpagesNoJekyll := true
  )
}

object BuildImplementation {
  import sbt.{url, file}
  import sbt.{Developer, Resolver, Watched, Compile, Test}
  import sbtdynver.DynVerPlugin.{autoImport => DynVerKeys}

  // This should be added to upstream sbt.
  def GitHub(org: String, project: String): java.net.URL =
    url(s"https://github.com/$org/$project")
  def GitHubDev(handle: String, fullName: String, email: String) =
    Developer(handle, fullName, email, url(s"https://github.com/$handle"))

  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin.{autoImport => ReleaseEarlyKeys}

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildPublishSettings: Seq[Def.Setting[_]] = Seq(
    ReleaseEarlyKeys.releaseEarlyWith := ReleaseEarlyKeys.SonatypePublisher,
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(ThisRepo),
    Keys.licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    Keys.developers := List(
      GitHubDev("Duhemm", "Martin Duhem", "martin.duhem@gmail.com"),
      GitHubDev("jvican", "Jorge Vicente Cantero", "jorge@vican.me")
    ),
  )

  import ch.epfl.scala.sbt.release.ReleaseEarly
  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    Keys.publishArtifact in Test := false
  )

  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := "2.12.4",
    Keys.triggeredMessage := Watched.clearWhenTriggered,
    Keys.resolvers := {
      val oldResolvers = Keys.resolvers.value
      val sonatypeStaging = Resolver.sonatypeRepo("staging")
      val scalametaResolver = Resolver.bintrayRepo("scalameta", "maven")
      (sonatypeStaging +: scalametaResolver +: oldResolvers).distinct
    }
  ) ++ buildPublishSettings

  import sbt.{CrossVersion, compilerPlugin}
  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    Pgp.PgpKeys.pgpPublicRing := {
      if (Keys.insideCI.value) file("/drone/.gnupg/pubring.asc")
      else Pgp.PgpKeys.pgpPublicRing.value
    },
    Pgp.PgpKeys.pgpSecretRing := {
      if (Keys.insideCI.value) file("/drone/.gnupg/secring.asc")
      else Pgp.PgpKeys.pgpPublicRing.value
    },
    ReleaseEarlyKeys.releaseEarlyPublish := {
      // We do `sonatypeReleaseAll` when all modules have been released, it's faster.
      Keys.streams.value.log.info(Feedback.logReleaseSonatype(Keys.name.value))
      Pgp.PgpKeys.publishSigned.value
    },
    Keys.scalacOptions := reasonableCompileOptions,
    Keys.scalacOptions ++= {
      if (Keys.scalaBinaryVersion.value.startsWith("2.10")) Nil
      else List("-Yrangepos")
    },
    Keys.libraryDependencies ++= {
      if (Keys.scalaBinaryVersion.value.startsWith("2.10")) Nil
      else
        List(
          compilerPlugin("org.scalameta" % "semanticdb-scalac" % "2.1.5" cross CrossVersion.full)
        )
    },
    // Legal requirement: license and notice files must be in the published jar
    Keys.resources in Compile ++= BuildDefaults.getLicense.value,
    Keys.publishArtifact in Test := false,
    Keys.publishArtifact in (Compile, Keys.packageDoc) := {
      val output = DynVerKeys.dynverGitDescribeOutput.value
      val version = Keys.version.value
      BuildDefaults.publishDocAndSourceArtifact(output, version)
    },
    Keys.publishArtifact in (Compile, Keys.packageSrc) := {
      val output = DynVerKeys.dynverGitDescribeOutput.value
      val version = Keys.version.value
      BuildDefaults.publishDocAndSourceArtifact(output, version)
    },
  )

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions = "-Xmx4g" :: "-Xms2g" :: Nil

  object BuildDefaults {
    import java.io.File
    import sbt.ScriptedPlugin.{autoImport => ScriptedKeys}

    private def createScriptedSetup(testDir: File) = {
      s"""
         |bloopConfigDir in Global := file("$testDir/.bloop-config")
         |TaskKey[Unit]("registerDirectory") := {
         |  val dir = (baseDirectory in ThisBuild).value
         |  IO.write(file("$testDir/.bloop-config/base-directory"), dir.getAbsolutePath)
         |}
         |TaskKey[Unit]("checkInstall") := {
         |  Thread.sleep(1000) // Let's wait a little bit because of OS's IO latency
         |  val mostRecentStamp = (bloopConfigDir.value ** "*.config").get.map(_.lastModified).min
         |  (System.currentTimeMillis() - mostRecentStamp)
         |  val diff = (System.currentTimeMillis() - mostRecentStamp) / 1000
         |  if (diff <= 5 * 60) () // If it happened in the last 5 minutes, this is ok!
         |  else sys.error("The sbt plugin didn't write any file")
         |}
    """.stripMargin
    }

    private val scriptedTestContents = {
      """> show bloopConfigDir
        |# Some projects need to compile to generate resources. We do it now so that the configuration
        |# that we generate doesn't appear too old.
        |> resources
        |> registerDirectory
        |> installBloop
        |> checkInstall
        |> copyContentOutOfScripted
      """.stripMargin
    }

    import sbt.librarymanagement.Artifact
    import ch.epfl.scala.sbt.maven.MavenPluginKeys
    val mavenPluginBuildSettings: Seq[Def.Setting[_]] = List(
      MavenPluginKeys.mavenPlugin := true,
      Keys.publishLocal := Keys.publishM2.value,
      Keys.classpathTypes += "maven-plugin",
      // This is a bug in sbt, so we fix it here.
      Keys.makePomConfiguration :=
        Keys.makePomConfiguration.value.withIncludeTypes(Keys.classpathTypes.value),
      Keys.libraryDependencies ++= List(
        Dependencies.mavenCore,
        Dependencies.mavenPluginApi,
        Dependencies.mavenPluginAnnotations,
        // We add an explicit dependency to the maven-plugin artifact in the dependent plugin
        Dependencies.mavenScalaPlugin
          .withExplicitArtifacts(Vector(Artifact("scala-maven-plugin", "maven-plugin", "jar")))
      ),
    )

    val fixScalaVersionForSbtPlugin: Def.Initialize[String] = Def.setting {
      val orig = Keys.scalaVersion.value
      val is013 = (Keys.sbtVersion in Keys.pluginCrossBuild).value.startsWith("0.13")
      if (is013) "2.10.6" else orig
    }

    private val NewLine = System.lineSeparator
    import sbt.io.syntax.singleFileFinder
    val scriptedSettings: Seq[Def.Setting[_]] = List(
      ScriptedKeys.scriptedBufferLog := true,
      ScriptedKeys.sbtTestDirectory := (Keys.baseDirectory in ThisBuild).value / "integration-tests",
      BuildKeys.scriptedAddSbtBloop := {
        val addSbtPlugin =
          s"""addSbtPlugin("${Keys.organization.value}" % "${Keys.name.value}" % "${Keys.version.value}")$NewLine"""
        val testPluginSrc = Keys.baseDirectory
          .in(ThisBuild)
          .value / "project" / "TestPlugin.scala"
        val tests =
          (ScriptedKeys.sbtTestDirectory.value / "integration-projects").*(AllPassFilter).get
        tests.foreach { testDir =>
          IO.copyFile(testPluginSrc, testDir / "project" / "TestPlugin.scala")
          IO.createDirectory(testDir / ".bloop-config")
          IO.write(testDir / "project" / "test-config.sbt", addSbtPlugin)
          IO.write(testDir / "test-config.sbt", createScriptedSetup(testDir))
          IO.write(testDir / "test", scriptedTestContents)
        }
      },
    )

    // From sbt-sensible https://gitlab.com/fommil/sbt-sensible/issues/5, legal requirement
    val getLicense: Def.Initialize[Task[Seq[File]]] = Def.task {
      val orig = (Keys.resources in Compile).value
      val base = Keys.baseDirectory.value
      val root = (Keys.baseDirectory in ThisBuild).value

      def fileWithFallback(name: String): File =
        if ((base / name).exists) base / name
        else if ((root / name).exists) root / name
        else throw new IllegalArgumentException(s"legal file $name must exist")

      Seq(fileWithFallback("LICENSE.md"), fileWithFallback("NOTICE.md"))
    }

    /**
     * This setting figures out whether the version is a snapshot or not and configures
     * the source and doc artifacts that are published by the build.
     *
     * Snapshot is a term with no clear definition. In this code, a snapshot is a revision
     * that is dirty, e.g. has time metadata in its representation. In those cases, the
     * build will not publish doc and source artifacts by any of the publishing actions.
     */
    def publishDocAndSourceArtifact(info: Option[GitDescribeOutput], version: String): Boolean = {
      val isStable = info.map(_.dirtySuffix.value.isEmpty)
      !isStable.map(stable => !stable || version.endsWith("-SNAPSHOT")).getOrElse(false)
    }
  }
}

object Header {
  val intro: String =
    """      _____            __         ______           __
      |     / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
      |     \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/
      |    ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
      |   /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/
      |
      |   ***********************************************************
      |   ***       Welcome to the build of `loooooooooop`        ***
      |   ***        An effort funded by the Scala Center         ***
      |   ***********************************************************
    """.stripMargin
}
