package build

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import bintray.BintrayKeys
import ch.epfl.scala.sbt.release.Feedback
import com.typesafe.sbt.SbtPgp.{autoImport => Pgp}
import pl.project13.scala.sbt.JmhPlugin.JmhKeys
import sbt.{AutoPlugin, BuildPaths, Command, Def, Keys, PluginTrigger, Plugins, Task, ThisBuild}
import sbt.io.{AllPassFilter, IO}
import sbt.io.syntax.fileToRichFile
import sbt.librarymanagement.syntax.stringToOrganization
import sbt.util.FileFunction
import sbtassembly.PathList
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

  final val BenchmarkBridgeProject =
    createScalaCenterProject("compiler-benchmark", file(s"$AbsolutePath/benchmark-bridge"))
  final val BenchmarkBridgeBuild = BuildRef(BenchmarkBridgeProject.build)
  final val BenchmarkBridgeCompilation = ProjectRef(BenchmarkBridgeProject.build, "compilation")

  import sbt.{Test, TestFrameworks, Tests}
  val buildBase = Keys.baseDirectory in ThisBuild
  val integrationStagingBase =
    Def.taskKey[File]("The base directory for sbt staging in all versions.")
  val integrationSetUpBloop =
    Def.taskKey[Unit]("Generate the bloop config for integration tests.")
  val buildIntegrationsIndex =
    Def.taskKey[File]("A csv index with complete information about our integrations.")
  val buildIntegrationsBase = Def.settingKey[File]("The base directory for our integration builds.")
  val nailgunClientLocation = Def.settingKey[sbt.File]("Where to find the python nailgun client")
  val updateHomebrewFormula = Def.taskKey[Unit]("Update Homebrew formula")

  // This has to be change every time the bloop config files format changes.
  val schemaVersion = Def.settingKey[String]("The schema version for our bloop build.")

  val testSettings: Seq[Def.Setting[_]] = List(
    Keys.testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    Keys.libraryDependencies ++= List(Dependencies.junit % Test),
    nailgunClientLocation := buildBase.value / "nailgun" / "pynailgun" / "ng.py",
  )

  val integrationTestSettings: Seq[Def.Setting[_]] = List(
    integrationStagingBase := {
      // Use the default staging directory, we don't care.
      val state = Keys.state.value
      val globalBase = sbt.BuildPaths.getGlobalBase(state)
      sbt.BuildPaths.getStagingDirectory(state, globalBase)
    },
    buildIntegrationsIndex := {
      val staging = integrationStagingBase.value
      staging / s"bloop-integrations-${BuildKeys.schemaVersion.in(sbt.Global).value}.csv"
    },
    buildIntegrationsBase := (Keys.baseDirectory in ThisBuild).value / "build-integrations",
    integrationSetUpBloop := BuildImplementation.integrationSetUpBloop.value,
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
  def bloopInfoKeys(nativeBridge: Reference, jsBridge: Reference) = {
    val zincKey = BuildInfoKey.constant("zincVersion" -> Dependencies.zincVersion)
    val developersKey = BuildInfoKey.map(Keys.developers) {
      case (k, devs) => k -> devs.map(_.name)
    }
    val nativeBridgeKey = BuildInfoKey.map(Keys.ivyModule in nativeBridge) {
      case (_, module) =>
        "nativeBridge" -> module.withModule(sbt.util.Logger.Null)((_, mod, _) =>
          mod.getModuleRevisionId.getName)
    }
    val jsBridgeKey = BuildInfoKey.map(Keys.ivyModule in jsBridge) {
      case (_, module) =>
        "jsBridge" -> module.withModule(sbt.util.Logger.Null)((_, mod, _) =>
          mod.getModuleRevisionId.getName)
    }
    val commonKeys = List[BuildInfoKey](
      Keys.organization,
      Keys.name,
      Keys.version,
      Keys.scalaVersion,
      Keys.sbtVersion,
      buildIntegrationsIndex,
      nailgunClientLocation
    )
    commonKeys ++ List(zincKey, developersKey, nativeBridgeKey, jsBridgeKey)
  }

  import sbtassembly.{AssemblyKeys, MergeStrategy}
  val assemblySettings: Seq[Def.Setting[_]] = List(
    Keys.mainClass in AssemblyKeys.assembly := Some("bloop.Bloop"),
    Keys.test in AssemblyKeys.assembly := {},
    AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly := {
      case "LICENSE.md" => MergeStrategy.first
      case "NOTICE.md" => MergeStrategy.first
      case PathList("io", "github", "soc", "directories", _ @ _*) => MergeStrategy.first
      case x =>
        val oldStrategy = (AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly).value
        oldStrategy(x)
    }
  )

  import sbtbuildinfo.BuildInfoKeys
  def benchmarksSettings(dep: Reference): Seq[Def.Setting[_]] = List(
    Keys.skip in Keys.publish := true,
    BuildInfoKeys.buildInfoKeys := Seq[BuildInfoKey](Keys.resourceDirectory in sbt.Test in dep),
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
        "-DbloopVersion=" + Keys.version.in(dep).value,
        "-DbloopRef=" + refOf(Keys.version.in(dep).value),
        "-Dbloop.jar=" + AssemblyKeys.assemblyOutputPath.in(AssemblyKeys.assembly).in(dep).value,
        "-Dgit.localdir=" + buildBase.value.getAbsolutePath
      )
    },
    Keys.run in JmhKeys.Jmh :=
      (Keys.run in JmhKeys.Jmh).dependsOn(AssemblyKeys.assembly.in(dep)).evaluated,
    Keys.runMain in JmhKeys.Jmh :=
      (Keys.runMain in JmhKeys.Jmh).dependsOn(AssemblyKeys.assembly.in(dep)).evaluated
  )

  import com.typesafe.sbt.site.SitePlugin.{autoImport => SiteKeys}
  import com.typesafe.sbt.site.hugo.HugoPlugin.{autoImport => HugoKeys}
  import com.typesafe.sbt.sbtghpages.GhpagesPlugin.{autoImport => GhpagesKeys}
  import com.typesafe.sbt.SbtGit.GitKeys
  import sbt.io.GlobFilter
  val websiteSettings: Seq[Def.Setting[_]] = Seq(
    Keys.sourceDirectory in HugoKeys.Hugo := Keys.baseDirectory.value,
    Keys.includeFilter in HugoKeys.Hugo :=
      (Keys.includeFilter in SiteKeys.makeSite).value || GlobFilter("*.svg") || GlobFilter(
        "bloop-schema.json"),
    HugoKeys.baseURL in HugoKeys.Hugo := sbt.uri("https://scalacenter.github.io/bloop"),
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

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    BuildKeys.schemaVersion := "2.2",
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    Keys.publishArtifact in Test := false,
    Pgp.pgpPublicRing := {
      if (Keys.insideCI.value) file("/drone/.gnupg/pubring.asc")
      else Pgp.pgpPublicRing.value
    },
    Pgp.pgpSecretRing := {
      if (Keys.insideCI.value) file("/drone/.gnupg/secring.asc")
      else Pgp.pgpSecretRing.value
    },
  )

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := "2.12.4",
    Keys.triggeredMessage := Watched.clearWhenTriggered,
    Keys.resolvers := {
      val oldResolvers = Keys.resolvers.value
      val scalacenterResolver = Resolver.bintrayRepo("scalacenter", "releases")
      val scalametaResolver = Resolver.bintrayRepo("scalameta", "maven")
      (oldResolvers :+ scalametaResolver :+ scalacenterResolver).distinct
    },
    ReleaseEarlyKeys.releaseEarlyWith := {
      // Only tag releases go directly to Maven Central, the rest go to bintray!
      val isOnlyTag = DynVerKeys.dynverGitDescribeOutput.value.map(v =>
        v.commitSuffix.isEmpty && v.dirtySuffix.value.isEmpty)
      if (isOnlyTag.getOrElse(false)) ReleaseEarlyKeys.SonatypePublisher
      else ReleaseEarlyKeys.BintrayPublisher
    },
    BintrayKeys.bintrayOrganization := Some("scalacenter"),
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

  import sbt.{CrossVersion, compilerPlugin}
  final val metalsSettings: Seq[Def.Setting[_]] = Seq(
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
  )

  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    BintrayKeys.bintrayRepository := "releases",
    BintrayKeys.bintrayPackage := "bloop",
    // Add some metadata that is useful to see in every on-merge bintray release
    BintrayKeys.bintrayPackageLabels := List("productivity", "build", "server", "cli", "tooling"),
    BintrayKeys.bintrayVersionAttributes ++= {
      import bintry.Attr
      Map(
        "zinc" -> Seq(Attr.String(Dependencies.zincVersion)),
        "nailgun" -> Seq(Attr.String(Dependencies.nailgunVersion))
      )
    },
    ReleaseEarlyKeys.releaseEarlyPublish := BuildDefaults.releaseEarlyPublish.value,
    Keys.scalacOptions := reasonableCompileOptions,
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
  ) ++ metalsSettings

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions = "-Xmx4g" :: "-Xms2g" :: "-XX:ReservedCodeCacheSize=512m" :: "-XX:MaxInlineLevel=20" :: Nil

  object BuildDefaults {
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

    import sbt.ScriptedPlugin.{autoImport => ScriptedKeys}
    val scriptedSettings: Seq[Def.Setting[_]] = List(
      ScriptedKeys.scriptedBufferLog := false,
      ScriptedKeys.scriptedLaunchOpts := {
        ScriptedKeys.scriptedLaunchOpts.value ++
          Seq("-Xmx1024M", "-Dplugin.version=" + Keys.version.value)
      }
    )

    val releaseEarlyPublish: Def.Initialize[Task[Unit]] = Def.task {
      val logger = Keys.streams.value.log
      val name = Keys.name.value
      // We force publishSigned for all of the modules, yes or yes.
      if (ReleaseEarlyKeys.releaseEarlyWith.value == ReleaseEarlyKeys.SonatypePublisher) {
        logger.info(Feedback.logReleaseSonatype(name))
      } else {
        logger.info(Feedback.logReleaseBintray(name))
      }

      Pgp.PgpKeys.publishSigned.value
    }

    val fixScalaVersionForSbtPlugin: Def.Initialize[String] = Def.setting {
      val orig = Keys.scalaVersion.value
      val is013 = (Keys.sbtVersion in Keys.pluginCrossBuild).value.startsWith("0.13")
      if (is013) "2.10.7" else orig
    }

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

  import scala.sys.process.Process
  val integrationSetUpBloop = Def.task {
    import sbt.MessageOnlyException
    import java.util.Locale

    val buildIntegrationsBase = BuildKeys.buildIntegrationsBase.value
    val buildIndexFile = BuildKeys.buildIntegrationsIndex.value
    val schemaVersion = BuildKeys.schemaVersion.value
    val stagingBase = BuildKeys.integrationStagingBase.value.getCanonicalFile.getAbsolutePath
    val cacheDirectory = file(stagingBase) / "integrations-cache"

    val buildFiles = Set(
      buildIndexFile,
      buildIntegrationsBase / "sbt-0.13" / "build.sbt",
      buildIntegrationsBase / "sbt-0.13" / "project" / "Integrations.scala",
      buildIntegrationsBase / "sbt-0.13-2" / "build.sbt",
      buildIntegrationsBase / "sbt-0.13-2" / "project" / "Integrations.scala",
      buildIntegrationsBase / "sbt-1.0" / "build.sbt",
      buildIntegrationsBase / "sbt-1.0" / "project" / "Integrations.scala",
      buildIntegrationsBase / "global" / "src" / "main" / "scala" / "bloop" / "build" / "integrations" / "IntegrationPlugin.scala",
    )

    val cachedGenerate =
      FileFunction.cached(cacheDirectory, sbt.util.FileInfo.hash) { builds =>
        val isWindows: Boolean =
          System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")
        val globalPluginsBase = buildIntegrationsBase / "global"
        val globalSettingsBase = globalPluginsBase / "settings"
        val stagingProperty = s"-D${BuildPaths.StagingProperty}=${stagingBase}"
        val settingsProperty = s"-D${BuildPaths.GlobalSettingsProperty}=${globalSettingsBase}"
        val pluginsProperty = s"-D${BuildPaths.GlobalPluginsProperty}=${globalPluginsBase}"
        val indexProperty = s"-Dbloop.integrations.index=${buildIndexFile.getAbsolutePath}"
        val schemaProperty = s"-Dbloop.integrations.schemaVersion=$schemaVersion"
        val properties = stagingProperty :: indexProperty :: pluginsProperty :: settingsProperty :: schemaProperty :: Nil
        val toRun = "cleanAllBuilds" :: "bloopInstall" :: "buildIndex" :: Nil
        val cmdBase = if (isWindows) "cmd.exe" :: "/C" :: "sbt.bat" :: Nil else "sbt" :: Nil
        val cmd = cmdBase ::: properties ::: toRun

        IO.delete(buildIndexFile)

        val exitGenerate013 = Process(cmd, buildIntegrationsBase / "sbt-0.13").!
        if (exitGenerate013 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 0.13.")

        val exitGenerate0132 = Process(cmd, buildIntegrationsBase / "sbt-0.13-2").!
        if (exitGenerate0132 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 0.13 (2).")

        val exitGenerate10 = Process(cmd, buildIntegrationsBase / "sbt-1.0").!
        if (exitGenerate10 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 1.0.")

        Set(buildIndexFile)
      }
    cachedGenerate(buildFiles)
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
