package build

import java.io.File

import com.jsuereth.sbtpgp.SbtPgp.{autoImport => Pgp}
import sbt._
import sbt.io.IO
import sbt.io.syntax.fileToRichFile
import sbt.librarymanagement.syntax.stringToOrganization
import sbt.util.FileFunction
import sbtdynver.GitDescribeOutput
import sbt.internal.BuildLoader
import sbt.librarymanagement.MavenRepository
import sbt.util.Logger
import sbtbuildinfo.BuildInfoPlugin.{autoImport => BuildInfoKeys}
import com.geirsson.CiReleasePlugin
import build.GithubRelease.{keys => GHReleaseKeys}

object BuildPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  import sbt.plugins.IvyPlugin
  import com.jsuereth.sbtpgp.SbtPgp

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    JvmPlugin && CiReleasePlugin && IvyPlugin
  val autoImport = BuildKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    BuildImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    BuildImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    BuildImplementation.projectSettings
}

object BuildKeys {
  // Use absolute paths so that references work even if `ThisBuild` changes
  final val AbsolutePath = file(".").getCanonicalFile.getAbsolutePath

  private val isCiDisabled = sys.env.get("CI").isEmpty
  def createScalaCenterProject(name: String, f: File): RootProject = {
    if (isCiDisabled) RootProject(f)
    else {
      val headSha = new _root_.com.github.sbt.git.DefaultReadableGit(base = f, gitOverride = None)
        .withGit(_.headCommitSha)
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

  val buildBase = (ThisBuild / Keys.baseDirectory)
  val exportCommunityBuild = Def.taskKey[Unit]("Clone and export the community build.")
  val lazyFullClasspath =
    Def.taskKey[Seq[File]]("Return full classpath without forcing compilation")

  val bloopName = Def.settingKey[String]("The name to use in build info generated code")
  val updateHomebrewFormula = Def.taskKey[Unit]("Update Homebrew formula")
  val updateScoopFormula = Def.taskKey[Unit]("Update Scoop formula")
  val updateArchPackage = Def.taskKey[Unit]("Update AUR package")
  val createLocalHomebrewFormula = Def.taskKey[Unit]("Create local Homebrew formula")
  val createLocalScoopFormula = Def.taskKey[Unit]("Create local Scoop formula")
  val createLocalArchPackage = Def.taskKey[Unit]("Create local ArchLinux package build files")
  val bloopCoursierJson = Def.taskKey[File]("Generate a versioned install script")
  val bloopLocalCoursierJson = Def.taskKey[File]("Generate a versioned install script")

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
    )
  )

  val releaseSettings = Seq(
    GHReleaseKeys.ghreleaseTitle := { tagName =>
      tagName.toString
    },
    GHReleaseKeys.ghreleaseNotes := { tagName =>
      IO.read(buildBase.value / "notes" / s"$tagName.md")
    },
    GHReleaseKeys.ghreleaseRepoOrg := "scalacenter",
    GHReleaseKeys.ghreleaseRepoName := "bloop",
    GHReleaseKeys.ghreleaseAssets ++= {
      val baseDir = (ThisBuild / Keys.baseDirectory).value
      val releaseTargetDir = Keys.target.value / "ghrelease-assets"

      val originBloopWindowsBinary =
        baseDir / "bloop-artifacts" / "bloop-windows" / "bloop-windows"
      val originBloopLinuxBinary =
        baseDir / "bloop-artifacts" / "bloop-linux" / "bloop-linux"
      val originBloopMacosBinary =
        baseDir / "bloop-artifacts" / "bloop-macos" / "bloop-macos"
      val originBloopMacosM1Binary =
        baseDir / "bloop-artifacts" / "bloop-macos-m1" / "bloop-macos-m1"
      val targetBloopLinuxBinary = releaseTargetDir / "bloop-x86_64-pc-linux"
      val targetBloopWindowsBinary = releaseTargetDir / "bloop-x86_64-pc-win32.exe"
      val targetBloopMacosBinary = releaseTargetDir / "bloop-x86_64-apple-darwin"
      val targetBloopMacosM1Binary = releaseTargetDir / "bloop-aarch64-apple-darwin"

      IO.copyFile(originBloopWindowsBinary, targetBloopWindowsBinary)
      IO.copyFile(originBloopLinuxBinary, targetBloopLinuxBinary)
      IO.copyFile(originBloopMacosBinary, targetBloopMacosBinary)
      IO.copyFile(originBloopMacosM1Binary, targetBloopMacosM1Binary)

      val originBashCompletions = baseDir / "etc" / "bash-completions"
      val originZshCompletions = baseDir / "etc" / "zsh-completions"
      val originFishCompletions = baseDir / "etc" / "fish-completions"
      val targetBashCompletions = releaseTargetDir / "bash-completions"
      val targetZshCompletions = releaseTargetDir / "zsh-completions"
      val targetFishCompletions = releaseTargetDir / "fish-completions"
      IO.copyFile(originBashCompletions, targetBashCompletions)
      IO.copyFile(originZshCompletions, targetZshCompletions)
      IO.copyFile(originFishCompletions, targetFishCompletions)

      val coursierJson = ReleaseUtils.bloopCoursierJson.value
      List(
        coursierJson,
        targetBashCompletions,
        targetZshCompletions,
        targetFishCompletions,
        targetBloopLinuxBinary,
        targetBloopMacosBinary,
        targetBloopWindowsBinary,
        targetBloopMacosM1Binary
      )
    },
    createLocalHomebrewFormula := ReleaseUtils.createLocalHomebrewFormula.value,
    createLocalScoopFormula := ReleaseUtils.createLocalScoopFormula.value,
    createLocalArchPackage := ReleaseUtils.createLocalArchPackage.value,
    updateHomebrewFormula := ReleaseUtils.updateHomebrewFormula.value,
    updateScoopFormula := ReleaseUtils.updateScoopFormula.value,
    updateArchPackage := ReleaseUtils.updateArchPackage.value
  )

  import sbtbuildinfo.{BuildInfoKey, BuildInfoKeys}

  def benchmarksSettings(dep: Reference): Seq[Def.Setting[_]] = List(
    (Keys.publish / Keys.skip) := true,
    BuildInfoKeys.buildInfoKeys := {
      val fullClasspathFiles =
        BuildInfoKey.map(dep / Compile / BuildKeys.lazyFullClasspath) {
          case (key, value) => ("fullCompilationClasspath", value.toList)
        }
      Seq[BuildInfoKey](
        dep / Test / Keys.resourceDirectory,
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
        "-DbloopVersion=" + (dep / Keys.version).value,
        "-DbloopRef=" + refOf((dep / Keys.version).value),
        "-Dgit.localdir=" + buildBase.value.getAbsolutePath
      )
    }
  )
}

object BuildImplementation {
  import sbtdynver.DynVerPlugin.{autoImport => DynVerKeys}

  // This should be added to upstream sbt.
  def GitHub(org: String, project: String): java.net.URL =
    url(s"https://github.com/$org/$project")
  def GitHubDev(handle: String, fullName: String, email: String) =
    Developer(handle, fullName, email, url(s"https://github.com/$handle"))

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.cancelable := true,
    BuildKeys.schemaVersion := "4.2-refresh-3",
    (Test / Keys.testOptions) += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    (Test / Keys.publishArtifact) := false
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
      val sonatypeStaging = Resolver.sonatypeOssRepos("staging")
      (oldResolvers ++ sonatypeStaging).distinct
    },
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

  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    Keys.scalacOptions := {
      CrossVersion.partialVersion(Keys.scalaVersion.value) match {
        case Some((2, 13)) =>
          reasonableCompileOptions
            .filterNot(opt => opt == "-deprecation" || opt == "-Yno-adapted-args")
        case _ =>
          reasonableCompileOptions
      }
    },
    // Legal requirement: license and notice files must be in the published jar
    (Compile / Keys.resources) ++= BuildDefaults.getLicense.value,
    (Compile / Keys.doc / Keys.sources) := Nil,
    (Test / Keys.doc / Keys.sources) := Nil,
    (Test / Keys.publishArtifact) := false,
    (Compile / Keys.packageDoc / Keys.publishArtifact) := {
      val output = DynVerKeys.dynverGitDescribeOutput.value
      val version = Keys.version.value
      BuildDefaults.publishDocAndSourceArtifact(output, version)
    },
    (Compile / Keys.packageSrc / Keys.publishArtifact) := {
      val output = DynVerKeys.dynverGitDescribeOutput.value
      val version = Keys.version.value
      BuildDefaults.publishDocAndSourceArtifact(output, version)
    },
    (Compile / Keys.publishLocalConfiguration) :=
      Keys.publishLocalConfiguration.value.withOverwrite(true)
  )

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions =
    "-Xmx3g" :: "-Xms1g" :: "-XX:ReservedCodeCacheSize=512m" :: "-XX:MaxInlineLevel=20" :: Nil

  final val javacOpts =
    "-source" :: "1.8" :: "-target" :: "1.8" :: Nil

  object BuildDefaults {
    def exportProjectsInTestResources(
        baseDir: File,
        log: Logger,
        enableCache: Boolean,
        bloopVersion: String
    ): Seq[File] = {
      import java.util.Locale
      val isWindows: Boolean =
        System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")

      // Generate bloop configuration files for projects we use in our test suite upfront
      val resourcesDir = baseDir / "frontend" / "src" / "test" / "resources"
      val pluginSourceDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
      val projectDirs = resourcesDir.listFiles().filter(_.isDirectory)
      projectDirs.flatMap { projectDir =>
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
          log.info(s"Generating bloop configuration files for ${projectDir}")
          val cmd = {
            val isGithubAction = sys.env.get("GITHUB_WORKFLOW").nonEmpty
            if (isWindows && isGithubAction)
              "sh" :: "-c" :: s"""sbt -DbloopVersion=${bloopVersion} "bloopInstall"""" :: Nil
            else if (isWindows)
              "cmd.exe" :: "/C" :: "sbt.bat" :: s"-DbloopVersion=${bloopVersion}" :: "bloopInstall" :: Nil
            else
              "sbt" :: s"-DbloopVersion=${bloopVersion}" :: "bloopInstall" :: Nil
          }
          val exitGenerate = Process(cmd, projectDir).!
          if (exitGenerate != 0)
            throw new sbt.MessageOnlyException(
              s"Failed to generate bloop config for resource project: ${projectDir}."
            )
          log.success(s"Generated bloop configuration files for ${projectDir}")
          changedFiles
        }
        val bloopConfigDir = projectDir / "bloop-config"
        val bloopConfigExists =
          bloopConfigDir.exists && bloopConfigDir.listFiles().exists(_.name.endsWith(".json"))
        val onlyOnCacheChange = enableCache && bloopConfigExists
        if (onlyOnCacheChange) {
          val cached = FileFunction.cached(cacheDirectory, sbt.util.FileInfo.hash) { changedFiles =>
            generate(changedFiles)
          }

          cached(projectsFiles ++ pluginFiles)
        } else generate(Set.empty)
        sbt.io.Path.allSubpaths(projectDir).map(_._1).toList
      }.distinct
    }

    def getStagingDirectory(state: State): File = {
      // Use the default staging directory, we don't care if the user changed it.
      val globalBase = sbt.BuildPaths.getGlobalBase(state)
      sbt.BuildPaths.getStagingDirectory(state, globalBase)
    }

    val frontendTestBuildSettings: Seq[Def.Setting[_]] = {
      sbtbuildinfo.BuildInfoPlugin.buildInfoScopedSettings(Test) ++ List(
        BuildKeys.bloopCoursierJson := ReleaseUtils.bloopCoursierJson.value,
        BuildKeys.bloopLocalCoursierJson := ReleaseUtils.bloopLocalCoursierJson.value,
        (Test / BuildInfoKeys.buildInfoKeys) := {
          import sbtbuildinfo.BuildInfoKey
          val junitTestJars = BuildInfoKey.map((Test / Keys.externalDependencyClasspath)) {
            case (_, classpath) =>
              val jars = classpath.map(_.data.getAbsolutePath)
              val junitJars = jars.filter(j => j.contains("junit") || j.contains("hamcrest"))
              "junitTestJars" -> junitJars
          }
          val sampleSourceGenerator = (Test / Keys.resourceDirectory).value / "source-generator.py"

          List(
            "sampleSourceGenerator" -> sampleSourceGenerator,
            "semanticdbVersion" -> Dependencies.semanticdbVersion,
            junitTestJars,
            BuildKeys.bloopCoursierJson,
            (ThisBuild / Keys.baseDirectory)
          )
        },
        (Test / BuildInfoKeys.buildInfoPackage) := "bloop.internal.build",
        (Test / BuildInfoKeys.buildInfoObject) := "BuildTestInfo"
      )
    }

    // From sbt-sensible https://gitlab.com/fommil/sbt-sensible/issues/5, legal requirement
    val getLicense: Def.Initialize[Task[Seq[File]]] = Def.task {
      val orig = (Compile / Keys.resources).value
      val base = Keys.baseDirectory.value
      val root = (ThisBuild / Keys.baseDirectory).value

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
  import scala.sys.process.Process
  import java.nio.file.Files
  val buildpressHomePath = System.getProperty("user.home") + "/.buildpress"
  def exportCommunityBuild(
      buildpress: Reference,
      sbtBloop: Reference
  ) = Def.taskDyn {
    val isWindows: Boolean =
      System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")
    if (isWindows) Def.task(println("Skipping export community build in Windows."))
    else {
      var regenerate: Boolean = false
      val state = Keys.state.value
      val globalBase = sbt.BuildPaths.getGlobalBase(state)
      val stagingDir = sbt.BuildPaths.getStagingDirectory(state, globalBase)
      java.nio.file.Files.createDirectories(stagingDir.toPath)
      val cacheDirectory = stagingDir./("community-build-cache")
      val regenerationFile = stagingDir./("regeneration-file.txt")
      val s = Keys.streams.value
      val mainClass = "buildpress.Main"
      val bloopVersion = Keys.version.value
      Def.task {
        // Publish the projects before we invoke buildpress
        (sbtBloop / Keys.publishLocal).value

        val file = (buildpress / Compile / Keys.resourceDirectory).value
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
        val classpath = (buildpress / Compile / Keys.fullClasspath).value
        val runner = ((buildpress / Compile / Keys.run) / Keys.runner).value
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
          val classpathKey = (dep / sbt.ConfigKey(c) / Keys.productDirectories)
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
