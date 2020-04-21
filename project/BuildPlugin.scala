package build

import java.io.File

import bintray.BintrayKeys
import ch.epfl.scala.sbt.release.Feedback
import com.jsuereth.sbtpgp.SbtPgp.{autoImport => Pgp}
import sbt.{
  AutoPlugin,
  BuildPaths,
  Def,
  Keys,
  PluginTrigger,
  Plugins,
  State,
  Task,
  ThisBuild,
  uri,
  Reference
}
import sbt.io.IO
import sbt.io.syntax.fileToRichFile
import sbt.librarymanagement.syntax.stringToOrganization
import sbt.util.FileFunction
import sbtassembly.PathList
import sbtdynver.GitDescribeOutput
import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin.{autoImport => ReleaseEarlyKeys}
import sbt.internal.BuildLoader
import sbt.librarymanagement.MavenRepository
import build.BloopShadingPlugin.{autoImport => BloopShadingKeys}

object BuildPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  import sbt.plugins.IvyPlugin
  import com.jsuereth.sbtpgp.SbtPgp
  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    JvmPlugin && ReleaseEarlyPlugin && SbtPgp && IvyPlugin
  val autoImport = BuildKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    BuildImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    BuildImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    BuildImplementation.projectSettings
}

object BuildKeys {
  import sbt.{RootProject, ProjectRef, BuildRef, file, uri}

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
      val headSha = new _root_.com.typesafe.sbt.git.DefaultReadableGit(f).withGit(_.headCommitSha)
      headSha match {
        case Some(commit) => RootProject(uri(s"https://github.com/scalacenter/${name}.git#$commit"))
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
  val buildIntegrationsBase = Def.settingKey[File]("The base directory for our integration builds.")
  val twitterDodo = Def.settingKey[File]("The location of Twitter's dodo build tool")
  val exportCommunityBuild = Def.taskKey[Unit]("Clone and export the community build.")
  val lazyFullClasspath =
    Def.taskKey[Seq[File]]("Return full classpath without forcing compilation")

  val bloopName = Def.settingKey[String]("The name to use in build info generated code")
  val nailgunClientLocation = Def.settingKey[sbt.File]("Where to find the python nailgun client")
  val updateHomebrewFormula = Def.taskKey[Unit]("Update Homebrew formula")
  val updateScoopFormula = Def.taskKey[Unit]("Update Scoop formula")
  val updateArchPackage = Def.taskKey[Unit]("Update AUR package")
  val createLocalHomebrewFormula = Def.taskKey[Unit]("Create local Homebrew formula")
  val createLocalScoopFormula = Def.taskKey[Unit]("Create local Scoop formula")
  val createLocalArchPackage = Def.taskKey[Unit]("Create local ArchLinux package build files")
  val bloopCoursierJson = Def.taskKey[File]("Generate a versioned install script")
  val releaseEarlyAllModules = Def.taskKey[Unit]("Release early all modules")
  val publishLocalAllModules = Def.taskKey[Unit]("Publish all modules locally")
  val generateInstallationWitness =
    Def.taskKey[File]("Generate a witness file to know which version is installed locally")

  val gradleIntegrationDirs = sbt.AttributeKey[List[File]]("gradleIntegrationDirs")
  val fetchGradleApi = Def.taskKey[Unit]("Fetch Gradle API artifact")

  // This has to be change every time the bloop config files format changes.
  val schemaVersion = Def.settingKey[String]("The schema version for our bloop build.")

  val testSuiteSettings: Seq[Def.Setting[_]] = List(
    Keys.testFrameworks += new sbt.TestFramework("utest.runner.Framework"),
    Keys.libraryDependencies ++= List(
      Dependencies.utest % Test,
      Dependencies.pprint % Test
    )
  )

  val testSettings: Seq[Def.Setting[_]] = List(
    Keys.testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    Keys.libraryDependencies ++= List(
      Dependencies.junit % Test,
      Dependencies.difflib % Test
    ),
    nailgunClientLocation := buildBase.value / "nailgun" / "pynailgun" / "ng.py"
  )

  import sbt.Compile
  val buildpressSettings: Seq[Def.Setting[_]] = List(
    Keys.fork in Keys.run := true
  )

  import ohnosequences.sbt.GithubRelease.{keys => GHReleaseKeys}
  val releaseSettings = Seq(
    GHReleaseKeys.ghreleaseTitle := { tagName =>
      tagName.toString
    },
    GHReleaseKeys.ghreleaseNotes := { tagName =>
      IO.read(buildBase.value / "notes" / s"$tagName.md")
    },
    GHReleaseKeys.ghreleaseRepoOrg := "scalacenter",
    GHReleaseKeys.ghreleaseRepoName := "bloop",
    GHReleaseKeys.ghreleaseAssets += ReleaseUtils.bloopCoursierJson.value,
    GHReleaseKeys.ghreleaseAssets += Keys.target.value / "graalvm-binaries",
    createLocalHomebrewFormula := ReleaseUtils.createLocalHomebrewFormula.value,
    createLocalScoopFormula := ReleaseUtils.createLocalScoopFormula.value,
    createLocalArchPackage := ReleaseUtils.createLocalArchPackage.value,
    generateInstallationWitness := ReleaseUtils.generateInstallationWitness.value,
    updateHomebrewFormula := ReleaseUtils.updateHomebrewFormula.value,
    updateScoopFormula := ReleaseUtils.updateScoopFormula.value,
    updateArchPackage := ReleaseUtils.updateArchPackage.value
  )

  import sbtbuildinfo.{BuildInfoKey, BuildInfoKeys}
  final val BloopBackendInfoKeys: List[BuildInfoKey] = {
    val scalaJarsKey =
      BuildInfoKey.map(Keys.scalaInstance) { case (_, i) => "scalaJars" -> i.allJars.toList }
    List(Keys.scalaVersion, Keys.scalaOrganization, scalaJarsKey)
  }

  import sbt.util.Logger.{Null => NullLogger}
  def bloopInfoKeys(
      nativeBridge03: Reference,
      nativeBridge04: Reference,
      jsBridge06: Reference,
      jsBridge10: Reference
  ): List[BuildInfoKey] = {
    val zincKey = BuildInfoKey.constant("zincVersion" -> Dependencies.zincVersion)
    val developersKey =
      BuildInfoKey.map(Keys.developers) { case (k, devs) => k -> devs.map(_.name) }
    type Module = sbt.internal.librarymanagement.IvySbt#Module
    def fromIvyModule(id: String, e: BuildInfoKey.Entry[Module]): BuildInfoKey.Entry[String] = {
      BuildInfoKey.map(e) {
        case (_, module) =>
          id -> module.withModule(NullLogger)((_, mod, _) => mod.getModuleRevisionId.getName)
      }
    }

    // Add only the artifact name for 0.6 bridge because we replace it
    val jsBridge06Key = fromIvyModule("jsBridge06", Keys.ivyModule in jsBridge06)
    val jsBridge10Key = fromIvyModule("jsBridge10", Keys.ivyModule in jsBridge10)
    val nativeBridge03Key = fromIvyModule("nativeBridge03", Keys.ivyModule in nativeBridge03)
    val nativeBridge04Key = fromIvyModule("nativeBridge04", Keys.ivyModule in nativeBridge04)
    val bspKey = BuildInfoKey.constant("bspVersion" -> Dependencies.bspVersion)
    val extra = List(
      zincKey,
      developersKey,
      nativeBridge03Key,
      nativeBridge04Key,
      jsBridge06Key,
      jsBridge10Key,
      bspKey
    )
    val commonKeys = List[BuildInfoKey](
      Keys.organization,
      BuildKeys.bloopName,
      Keys.version,
      Keys.scalaVersion,
      Keys.sbtVersion,
      nailgunClientLocation
    )
    commonKeys ++ extra
  }

  // Unused, just like `cloneKafka`
  val GradleInfoKeys: List[BuildInfoKey] = List(
    BuildInfoKey.map(Keys.state) {
      case (_, state) =>
        val integrationDirs = state
          .get(gradleIntegrationDirs)
          .getOrElse(sys.error("Fatal: integration dirs for gradle were not computed"))
        "integrationDirs" -> integrationDirs
    }
  )

  import sbtassembly.{AssemblyKeys, MergeStrategy}
  val assemblySettings: Seq[Def.Setting[_]] = List(
    Keys.mainClass in AssemblyKeys.assembly := Some("bloop.Bloop"),
    Keys.test in AssemblyKeys.assembly := {},
    AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly := {
      case "LICENSE.md" => MergeStrategy.first
      case "NOTICE.md" => MergeStrategy.first
      case PathList("io", "github", "soc", "directories", _ @_*) => MergeStrategy.first
      case x =>
        val oldStrategy = (AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly).value
        oldStrategy(x)
    }
  )

  def shadedModuleSettings = List(
    BloopShadingKeys.shadingNamespace := "bloop.shaded"
  )

  def sbtPluginSettings(
      name: String,
      sbtVersion: String
  ): Seq[Def.Setting[_]] = List(
    Keys.name := name,
    Keys.sbtPlugin := true,
    Keys.sbtVersion := sbtVersion,
    Keys.target := (file("integrations") / "sbt-bloop" / "target" / sbtVersion).getAbsoluteFile,
    BintrayKeys.bintrayPackage := "sbt-bloop",
    BintrayKeys.bintrayOrganization := Some("sbt"),
    BintrayKeys.bintrayRepository := "sbt-plugin-releases",
    Keys.publishMavenStyle :=
      ReleaseEarlyKeys.releaseEarlyWith.value == ReleaseEarlyKeys.SonatypePublisher
  )

  def benchmarksSettings(dep: Reference): Seq[Def.Setting[_]] = List(
    Keys.skip in Keys.publish := true,
    BuildInfoKeys.buildInfoKeys := {
      val fullClasspathFiles =
        BuildInfoKey.map(BuildKeys.lazyFullClasspath.in(sbt.Compile).in(dep)) {
          case (key, value) => ("fullCompilationClasspath", value.toList)
        }
      Seq[BuildInfoKey](
        Keys.resourceDirectory in sbt.Test in dep,
        fullClasspathFiles
      )
    },
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
        "-Dgit.localdir=" + buildBase.value.getAbsolutePath
      )
    }
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

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.cancelable := true,
    BuildKeys.schemaVersion := "4.2-refresh-3",
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    Keys.onLoad := BuildDefaults.bloopOnLoad.value,
    Keys.publishArtifact in Test := false
  )

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := Dependencies.Scala212Version,
    sbt.nio.Keys.watchTriggeredMessage := sbt.Watch.clearScreenOnTrigger,
    // Keys.triggeredMessage := Watched.clearWhenTriggered,
    Keys.resolvers := {
      val oldResolvers = Keys.resolvers.value
      val scalacenterResolver = Resolver.bintrayRepo("scalacenter", "releases")
      val scalametaResolver = Resolver.bintrayRepo("scalameta", "maven")
      (oldResolvers :+ scalametaResolver :+ scalacenterResolver).distinct
    },
    ReleaseEarlyKeys.releaseEarlyWith := {
      /*
      // Only tag releases go directly to Maven Central, the rest go to bintray!
      val isOnlyTag = DynVerKeys.dynverGitDescribeOutput.value
        .map(v => v.commitSuffix.isEmpty && v.dirtySuffix.value.isEmpty)
      if (isOnlyTag.getOrElse(false)) ReleaseEarlyKeys.SonatypePublisher
      else ReleaseEarlyKeys.BintrayPublisher
       */
      ReleaseEarlyKeys.SonatypePublisher
    },
    BintrayKeys.bintrayOrganization := Some("scalacenter"),
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(ThisRepo),
    Keys.licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    Keys.developers := List(
      GitHubDev("jvican", "Jorge Vicente Cantero", "jorge@vican.me"),
      GitHubDev("Duhemm", "Martin Duhem", "martin.duhem@gmail.com")
    ),
    Keys.scmInfo := Some(
      sbt.ScmInfo(
        sbt.url("https://github.com/scalacenter/bloop"),
        "scm:git:git@github.com:scalacenter/bloop.git"
      )
    )
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
    }
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
    Keys.sources in (Compile, Keys.doc) := Nil,
    Keys.sources in (Test, Keys.doc) := Nil,
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
    Keys.publishLocalConfiguration in Compile :=
      Keys.publishLocalConfiguration.value.withOverwrite(true)
  ) // ++ metalsSettings

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions = "-Xmx3g" :: "-Xms1g" :: "-XX:ReservedCodeCacheSize=512m" :: "-XX:MaxInlineLevel=20" :: Nil

  object BuildDefaults {
    private final val kafka =
      uri("https://github.com/apache/kafka.git#57320981bb98086a0b9f836a29df248b1c0378c3")

    // Currently unused, we leave it here because we might need it in the future
    private def cloneKafka(state: State): State = {
      val staging = getStagingDirectory(state)
      sbt.Resolvers.git(new BuildLoader.ResolveInfo(kafka, staging, null, state)) match {
        case Some(f) => state.put(BuildKeys.gradleIntegrationDirs, List(f()))
        case None =>
          state.log.error("Kafka git reference is invalid and cannot be cloned"); state
      }
    }

    /** This onLoad hook will clone any repository required for the build tool integration tests.
     * In this case, we clone kafka so that the gradle plugin unit tests can access to its directory. */
    val bloopOnLoad: Def.Initialize[State => State] = Def.setting {
      Keys.onLoad.value.andThen { state =>
        if (sys.env.isDefinedAt("SKIP_TEST_RESOURCES_GENERATION")) state
        else exportProjectsInTestResources(state, enableCache = true)
      }
    }

    def exportProjectsInTestResources(state: State, enableCache: Boolean): State = {
      import java.util.Locale
      val isWindows: Boolean =
        System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")

      // Generate bloop configuration files for projects we use in our test suite upfront
      val resourcesDir = state.baseDir / "frontend" / "src" / "test" / "resources"
      val pluginSourceDir = state.baseDir / "integrations" / "sbt-bloop" / "src" / "main"
      val projectDirs = resourcesDir.listFiles().filter(_.isDirectory)
      projectDirs.foreach { projectDir =>
        val targetDir = projectDir / "target"
        val cacheDirectory = targetDir / "generation-cache-dir"
        if (sys.env.isDefinedAt("FORCE_TEST_RESOURCES_GENERATION"))
          IO.delete(cacheDirectory)
        java.nio.file.Files.createDirectories(cacheDirectory.toPath)

        val projectsFiles = sbt.io.Path
          .allSubpaths(projectDir)
          .map(_._1)
          .filter { f =>
            val filename = f.toString
            filename.endsWith(".sbt") || filename.endsWith(".scala")
          }
          .toSet

        val pluginFiles = sbt.io.Path
          .allSubpaths(pluginSourceDir)
          .map(_._1)
          .filter(f => f.toString.endsWith(".scala"))
          .toSet

        import scala.sys.process.Process

        val generate = { (changedFiles: Set[File]) =>
          state.log.info(s"Generating bloop configuration files for ${projectDir}")
          val cmd = {
            val isGithubAction = sys.env.get("GITHUB_WORKFLOW").nonEmpty
            if (isWindows && isGithubAction) "sh" :: "-c" :: "sbt bloopInstall" :: Nil
            else if (isWindows) "cmd.exe" :: "/C" :: "sbt.bat" :: "bloopInstall" :: Nil
            else "sbt" :: "bloopInstall" :: Nil
          }
          val exitGenerate = Process(cmd, projectDir).!
          if (exitGenerate != 0)
            throw new sbt.MessageOnlyException(
              s"Failed to generate bloop config for ${projectDir}."
            )
          state.log.success(s"Generated bloop configuration files for ${projectDir}")
          changedFiles
        }

        if (enableCache) {
          val cached = FileFunction.cached(cacheDirectory, sbt.util.FileInfo.hash) { changedFiles =>
            generate(changedFiles)
          }

          cached(projectsFiles ++ pluginFiles)
        } else generate(Set.empty)
      }

      state
    }

    val exportProjectsInTestResourcesCmd: sbt.Command =
      sbt.Command.command("exportProjectsInTestResources")(exportProjectsInTestResources(_, false))

    def getStagingDirectory(state: State): File = {
      // Use the default staging directory, we don't care if the user changed it.
      val globalBase = sbt.BuildPaths.getGlobalBase(state)
      sbt.BuildPaths.getStagingDirectory(state, globalBase)
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
        Dependencies.mavenInvoker,
        // We add an explicit dependency to the maven-plugin artifact in the dependent plugin
        Dependencies.mavenScalaPlugin
          .withExplicitArtifacts(Vector(Artifact("scala-maven-plugin", "maven-plugin", "jar")))
      )
    )

    import sbtbuildinfo.BuildInfoPlugin.{autoImport => BuildInfoKeys}
    val gradlePluginBuildSettings: Seq[Def.Setting[_]] = {
      sbtbuildinfo.BuildInfoPlugin.buildInfoScopedSettings(Test) ++ List(
        Keys.fork in Test := true,
        Keys.resolvers ++= List(
          MavenRepository("Gradle releases", "https://repo.gradle.org/gradle/libs-releases-local/")
        ),
        Keys.libraryDependencies ++= List(
          Dependencies.gradleCore,
          Dependencies.gradleToolingApi,
          Dependencies.groovy
        ),
        Keys.publishLocal := Keys.publishLocal.dependsOn(Keys.publishM2).value,
        Keys.unmanagedJars.in(Compile) := unmanagedJarsWithGradleApi.value,
        BuildKeys.fetchGradleApi := {
          val logger = Keys.streams.value.log
          val targetDir = (Keys.baseDirectory in Compile).value / "lib"
          if (!System.getProperty("java.specification.version").startsWith("1.")) ()
          else {
            // TODO: we may want to fetch it to a custom unmanaged lib directory under build
            GradleIntegration.fetchGradleApi(Dependencies.gradleVersion, targetDir, logger)
          }
        },
        // Only generate for tests (they are not published and can contain user-dependent data)
        BuildInfoKeys.buildInfo in Compile := Nil,
        BuildInfoKeys.buildInfoPackage in Test := "bloop.internal.build",
        BuildInfoKeys.buildInfoObject in Test := "BloopGradleIntegration"
      )
    }

    val frontendTestBuildSettings: Seq[Def.Setting[_]] = {
      sbtbuildinfo.BuildInfoPlugin.buildInfoScopedSettings(Test) ++ List(
        BuildKeys.bloopCoursierJson := ReleaseUtils.bloopCoursierJson.value,
        BuildInfoKeys.buildInfoKeys in Test := {
          import sbtbuildinfo.BuildInfoKey
          val junitTestJars = BuildInfoKey.map(Keys.externalDependencyClasspath in Test) {
            case (_, classpath) =>
              val jars = classpath.map(_.data.getAbsolutePath)
              val junitJars = jars.filter(j => j.contains("junit") || j.contains("hamcrest"))
              "junitTestJars" -> junitJars
          }

          List(junitTestJars, BuildKeys.bloopCoursierJson, Keys.baseDirectory in ThisBuild)
        },
        BuildInfoKeys.buildInfoPackage in Test := "bloop.internal.build",
        BuildInfoKeys.buildInfoObject in Test := "BuildTestInfo"
      )
    }

    lazy val unmanagedJarsWithGradleApi: Def.Initialize[Task[Keys.Classpath]] = Def.taskDyn {
      val unmanagedJarsTask = Keys.unmanagedJars.in(Compile).taskValue
      val _ = BuildKeys.fetchGradleApi.value
      Def.task(unmanagedJarsTask.value)
    }

    val millModuleBuildSettings: Seq[Def.Setting[_]] = List(
      Keys.libraryDependencies ++= List(
        Dependencies.mill
      )
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

    def releaseEarlyAllModules(projects: Seq[sbt.ProjectReference]): Def.Initialize[Task[Unit]] = {
      Def.taskDyn {
        val filter = sbt.ScopeFilter(
          sbt.inProjects(projects: _*),
          sbt.inConfigurations(sbt.Compile)
        )

        ReleaseEarlyKeys.releaseEarly.all(filter).map(_ => ())
      }
    }

    def publishLocalAllModules(projects: Seq[sbt.ProjectReference]): Def.Initialize[Task[Unit]] = {
      Def.taskDyn {
        val filter = sbt.ScopeFilter(
          sbt.inProjects(projects: _*),
          sbt.inConfigurations(sbt.Compile)
        )

        Keys.publishLocal.all(filter).map(_ => ())
      }
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
      !isStable.exists(stable => !stable || version.endsWith("-SNAPSHOT"))
    }
  }

  import java.util.Locale
  import sbt.MessageOnlyException
  import sbt.{Compile}
  import scala.sys.process.Process
  import java.nio.file.Files
  val buildpressHomePath = System.getProperty("user.home") + "/.buildpress"
  def exportCommunityBuild(
      buildpress: Reference,
      circeConfig210: Reference,
      circeConfig212: Reference,
      sbtBloop013: Reference,
      sbtBloop10: Reference
  ) = Def.taskDyn {
    val isWindows: Boolean =
      System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")
    if (isWindows) Def.task(println("Skipping export community build in Windows."))
    else {
      val baseDir = Keys.baseDirectory.in(ThisBuild).value
      val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"

      // Only sbt sources are added, add new plugin sources when other build tools are supported
      val allPluginSourceDirs = Set(
        baseDir / "config" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / "scala-2.10",
        baseDir / "config" / "src" / "main" / "scala-2.11",
        baseDir / "config" / "src" / "main" / "scala-2.12",
        baseDir / "config" / "src" / "main" / "scala-2.11-12",
        pluginMainDir / "scala",
        pluginMainDir / "scala-2.10",
        pluginMainDir / "scala-2.12",
        pluginMainDir / s"scala-sbt-0.13",
        pluginMainDir / s"scala-sbt-1.0"
      )

      val allPluginSourceFiles = allPluginSourceDirs.flatMap { sourceDir =>
        val sourcePath = sourceDir.toPath
        if (!Files.exists(sourcePath)) Nil
        else pathFilesUnder(sourcePath, "glob:**.{scala,java}").map(_.toFile)
      }.toSet

      var regenerate: Boolean = false
      val state = Keys.state.value
      val globalBase = sbt.BuildPaths.getGlobalBase(state)
      val stagingDir = sbt.BuildPaths.getStagingDirectory(state, globalBase)
      java.nio.file.Files.createDirectories(stagingDir.toPath)
      val cacheDirectory = stagingDir./("community-build-cache")
      val regenerationFile = stagingDir./("regeneration-file.txt")
      val cachedGenerate = FileFunction.cached(cacheDirectory, sbt.util.FileInfo.hash) { _ =>
        // Publish local snapshots via Twitter dodo's build tool for exporting the build to work
        val cmd = "bash" :: BuildKeys.twitterDodo.value.getAbsolutePath :: "--no-test" :: "finagle" :: Nil
        val dodoSetUp = Process(cmd, baseDir).!
        if (dodoSetUp != 0)
          throw new MessageOnlyException(
            "Failed to publish local snapshots for twitter projects."
          )

        // Write a dummy regeneration file for the caching to work
        regenerate = true
        IO.write(regenerationFile, "true")
        Set(regenerationFile)
      }

      val s = Keys.streams.value
      val mainClass = "buildpress.Main"
      val bloopVersion = Keys.version.value

      cachedGenerate(allPluginSourceFiles)
      Def.task {
        // Publish the projects before we invoke buildpress
        Keys.publishLocal.in(circeConfig210).value
        Keys.publishLocal.in(circeConfig212).value
        Keys.publishLocal.in(sbtBloop013).value
        Keys.publishLocal.in(sbtBloop10).value

        val file = Keys.resourceDirectory
          .in(Compile)
          .in(buildpress)
          .value
          ./("bloop-community-build.buildpress")

        // We regenerate again if something in the plugin sources has changed
        val regenerateArgs = if (regenerate) List("--regenerate") else Nil
        val buildpressArgs = List(
          "--input",
          file.toString,
          "--buildpress-home",
          buildpressHomePath,
          "--bloop-version",
          bloopVersion
        ) ++ regenerateArgs

        import sbt.internal.util.Attributed.data
        val classpath = (Keys.fullClasspath in Compile in buildpress).value
        val runner = (Keys.runner in (Compile, Keys.run) in buildpress).value
        runner.run(mainClass, data(classpath), buildpressArgs, s.log).get
      }
    }
  }

  import java.io.IOException
  import java.nio.file.attribute.BasicFileAttributes
  import java.nio.file.{FileSystems, FileVisitOption, FileVisitResult, FileVisitor, Files, Path}
  def pathFilesUnder(
      base: Path,
      pattern: String,
      maxDepth: Int = Int.MaxValue
  ): List[Path] = {
    val out = collection.mutable.ListBuffer.empty[Path]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)

    val visitor = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file)) out += file
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = FileVisitResult.CONTINUE

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    val opts = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(base, opts, maxDepth, visitor)
    out.toList
  }

  final lazy val lazyInternalDependencyClasspath: Def.Initialize[Task[Seq[File]]] = {
    Def.taskDyn {
      val currentProject = Keys.thisProjectRef.value
      val data = Keys.settingsData.value
      val deps = Keys.buildDependencies.value
      val conf = Keys.classpathConfiguration.value
      val self = Keys.configuration.value

      import scala.collection.JavaConverters._
      val visited = sbt.Classpaths.interSort(currentProject, conf, data, deps)
      val productDirs = (new java.util.LinkedHashSet[Task[Seq[File]]]).asScala
      for ((dep, c) <- visited) {
        if ((dep != currentProject) || (conf.name != c && self.name != c)) {
          val classpathKey = Keys.productDirectories in (dep, sbt.ConfigKey(c))
          productDirs += classpathKey.get(data).getOrElse(sbt.std.TaskExtra.constant(Nil))
        }
      }

      val generatedTask = productDirs.toList.join.map(_.flatten.distinct)
      Def.task(generatedTask.value)
    }
  }

  final lazy val lazyDependencyClasspath: Def.Initialize[Task[Seq[File]]] = Def.task {
    val internalClasspath = lazyInternalDependencyClasspath.value
    val externalClasspath = Keys.externalDependencyClasspath.value.map(_.data)
    internalClasspath ++ externalClasspath
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
