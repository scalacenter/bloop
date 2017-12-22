package build

import sbt.{AutoPlugin, Command, Def, Keys, PluginTrigger, Plugins}

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
  import sbt.{LocalProject, Reference, RootProject, ProjectRef, BuildRef, file}
  import sbt.librarymanagement.syntax.stringToOrganization
  def inProject(ref: Reference)(ss: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] =
    sbt.inScope(sbt.ThisScope.in(project = ref))(ss)

  def inProjectRefs(refs: Seq[Reference])(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    refs.flatMap(inProject(_)(ss))

  def inCompileAndTest(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    Seq(sbt.Compile, sbt.Test).flatMap(sbt.inConfig(_)(ss))

  // Use absolute paths so that references work even if `ThisBuild` changes
  final val AbsolutePath = file(".").getCanonicalFile.getAbsolutePath

  final val ZincProject = RootProject(file(s"$AbsolutePath/zinc"))
  final val ZincBuild = BuildRef(ZincProject.build)
  final val Zinc = ProjectRef(ZincProject.build, "zinc")
  final val ZincRoot = ProjectRef(ZincProject.build, "zincRoot")
  final val ZincBridge = ProjectRef(ZincProject.build, "compilerBridge")

  final val NailgunProject = RootProject(file(s"$AbsolutePath/nailgun"))
  final val NailgunBuild = BuildRef(NailgunProject.build)
  final val Nailgun = ProjectRef(NailgunProject.build, "nailgun")
  final val NailgunServer = ProjectRef(NailgunProject.build, "nailgun-server")
  final val NailgunExamples = ProjectRef(NailgunProject.build, "nailgun-examples")

  final val BenchmarkBridgeProject = RootProject(file(s"$AbsolutePath/benchmark-bridge"))
  final val BenchmarkBridgeBuild = BuildRef(BenchmarkBridgeProject.build)
  final val BenchmarkBridgeCompilation = ProjectRef(BenchmarkBridgeProject.build, "compilation")

  final val frontendProjectId = "frontend"
  final val FrontendProject = LocalProject(frontendProjectId)

  import sbtbuildinfo.BuildInfoKey
  final val BloopInfoKeys = {
    val zincVersion = Keys.version in ZincRoot
    val zincKey = BuildInfoKey.map(zincVersion) { case (k, version) => "zincVersion" -> version }
    val developersKey = BuildInfoKey.map(Keys.developers) {
      case (k, devs) => k -> devs.map(_.name)
    }
    val commonKeys = List[BuildInfoKey](Keys.name, Keys.version, Keys.scalaVersion, Keys.sbtVersion)
    commonKeys ++ List(zincKey, developersKey)
  }

  import sbt.Test
  val scriptedAddSbtBloop = Def.taskKey[Unit]("Add sbt-bloop to the test projects")
  val testSettings: Seq[Def.Setting[_]] = List(
    Keys.testFrameworks += new sbt.TestFramework("utest.runner.Framework"),
    Keys.libraryDependencies ++= List(
      Dependencies.utest % Test,
      Dependencies.junit % Test
    ),
  )

  import sbtassembly.AssemblyKeys
  val assemblySettings: Seq[Def.Setting[_]] = List(
    Keys.mainClass in AssemblyKeys.assembly := Some("bloop.Bloop"),
    Keys.test in AssemblyKeys.assembly := {}
  )

  import sbtbuildinfo.BuildInfoKeys
  val benchmarksSettings: Seq[Def.Setting[_]] = List(
    Keys.skip in Keys.publish := true,
    BuildInfoKeys.buildInfoKeys := Seq[BuildInfoKey](
      Keys.resourceDirectory in sbt.Test in FrontendProject),
    BuildInfoKeys.buildInfoPackage := "bloop.benchmarks",
    Keys.javaOptions ++= {
      def refOf(version: String) = {
        val HasSha = """([0-9a-f]{8})(?:\+\d{8}-\d{4})?""".r
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
        "-DbloopVersion=" + Keys.version.in(FrontendProject).value,
        "-DbloopRef=" + refOf(Keys.version.in(FrontendProject).value),
        "-Dbloop.jar=" + AssemblyKeys.assembly.in(FrontendProject).value,
        "-Dgit.localdir=" + Keys.baseDirectory.in(sbt.ThisBuild).value.getAbsolutePath
      )
    }
  )
}

object BuildImplementation {
  import sbt.{url, file}
  import sbt.{Developer, Resolver, Watched, Compile, Test}

  // This should be added to upstream sbt.
  def GitHub(org: String, project: String): java.net.URL =
    url(s"https://github.com/$org/$project")
  def GitHubDev(handle: String, fullName: String, email: String) =
    Developer(handle, fullName, email, url(s"https://github.com/$handle"))

  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin.{autoImport => ReleaseEarlyKeys}

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildPublishSettings: Seq[Def.Setting[_]] = Seq(
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(ThisRepo),
    Keys.licenses := Seq("BSD" -> url("http://opensource.org/licenses/BSD-3-Clause")),
    Keys.developers := List(
      GitHubDev("Duhemm", "Martin Duhem", "martin.duhem@gmail.com"),
      GitHubDev("jvican", "Jorge Vicente Cantero", "jorge@vican.me")
    ),
    ReleaseEarlyKeys.releaseEarlyWith := ReleaseEarlyKeys.SonatypePublisher,
  )

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    Keys.commands += Semanticdb.command(Keys.crossScalaVersions.value),
    Keys.commands ~= BuildDefaults.fixPluginCross _,
    Keys.commands += BuildDefaults.setupTests,
    Keys.onLoad := BuildDefaults.onLoad.value,
    Keys.publishArtifact in Test := false,
  )

  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.resolvers += Resolver.jcenterRepo,
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := "2.12.4",
    Keys.triggeredMessage := Watched.clearWhenTriggered,
  ) ++ buildPublishSettings

  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    Keys.scalacOptions in Compile := reasonableCompileOptions,
  )

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions = "-Xmx4g" :: "-Xms2g" :: Nil

  object BuildDefaults {
    import sbt.State
    /* This rounds off the trickery to set up those projects whose `overridingProjectSettings` have
     * been overriden because sbt has decided to initialize the settings from the sourcedep after. */
    val hijacked = sbt.AttributeKey[Boolean]("The hijacked option.")
    val onLoad: Def.Initialize[State => State] = Def.setting { (state: State) =>
      val globalSettings =
        List(Keys.onLoadMessage in sbt.Global := s"Setting up the integration builds.")
      def genProjectSettings(ref: sbt.ProjectRef) =
        BuildKeys.inProject(ref)(List(Keys.organization := "ch.epfl.scala"))
      val buildStructure = sbt.Project.structure(state)
      if (state.get(hijacked).getOrElse(false)) state.remove(hijacked)
      else {
        val hijackedState = state.put(hijacked, true)
        val extracted = sbt.Project.extract(hijackedState)
        val allZincProjects = buildStructure.allProjectRefs(BuildKeys.ZincBuild.build)
        val allNailgunProjects = buildStructure.allProjectRefs(BuildKeys.NailgunBuild.build)
        val allBenchmarkBridgeProjects =
          buildStructure.allProjectRefs(BuildKeys.BenchmarkBridgeBuild.build)
        val allProjects = allZincProjects ++ allNailgunProjects ++ allBenchmarkBridgeProjects
        val projectSettings = allProjects.flatMap(genProjectSettings)
        // NOTE: This is done because sbt does not handle session settings correctly. Should be reported upstream.
        val currentSession = sbt.Project.session(state)
        val currentProject = currentSession.current
        val currentSessionSettings =
          currentSession.append.get(currentProject).toList.flatten.map(_._1)
        val allSessionSettings = currentSessionSettings ++ currentSession.rawAppend
        extracted.append(globalSettings ++ projectSettings ++ allSessionSettings, hijackedState)
      }
    }

    def fixPluginCross(commands: Seq[Command]): Seq[Command] = {
      val pruned = commands.filterNot(p => p == sbt.WorkingPluginCross.oldPluginSwitch)
      sbt.WorkingPluginCross.pluginSwitch +: pruned
    }

    import java.io.File
    import sbt.io.{AllPassFilter, IO}
    import sbt.ScriptedPlugin.{autoImport => ScriptedKeys}

    /**
     * Helps with setting up the tests:
     * - Adds sbt-bloop to all the projects in `frontend/src/test/resources/projects`
     * - Runs scripted, so that the configuration files are generated.
     */
    val setupTests = Command.command("setupTests") { state =>
      s"^sbtBloop/${Keys.publishLocal.key.label}" ::
        s"sbtBloop/${BuildKeys.scriptedAddSbtBloop.key.label}" ::
        s"sbtBloop/${ScriptedKeys.scripted.key.label}" ::
        state
    }

    private def createScriptedSetup(testDir: File) = {
      s"""
         |bloopConfigDir in Global := file("$testDir/bloop-config")
         |TaskKey[Unit]("registerDirectory") := {
         |  val dir = (baseDirectory in ThisBuild).value
         |  IO.write(file("$testDir/bloop-config/base-directory"), dir.getAbsolutePath)
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

    private val testPluginContents = {
      """import sbt._
        |import Keys._
        |import bloop.SbtBloop.autoImport._
        |
        |object TestPlugin extends AutoPlugin {
        |  override def requires = bloop.SbtBloop
        |  override def trigger = allRequirements
        |
        |  object autoImport {
        |    lazy val copySources = taskKey[Unit]("Copy all generated sources")
        |    lazy val copySourcesIndividual = taskKey[Unit]("Copy generated sources")
        |  }
        |  import autoImport._
        |
        |  override def globalSettings: Seq[Setting[_]] = Seq(
        |    copySources := Def.taskDyn {
        |      val filter = ScopeFilter(sbt.inAnyProject)
        |      copySourcesIndividual.all(filter).map(_ => ())
        |    }.value
        |  )
        |
        |  override def projectSettings: Seq[Setting[_]] = Seq(
        |    copySourcesIndividual := {
        |      val outOfScriptedRoot = bloopConfigDir.value.getParentFile
        |      val inScriptedRoot = baseDirectory.in(ThisBuild).value
        |      def copy(managedSources: Seq[File]): Unit = {
        |        for { src <- managedSources
        |              generatedPath <- IO.relativize(inScriptedRoot, src)
        |              generatedOutOfScripted = outOfScriptedRoot / generatedPath } {
        |          IO.copyFile(src, generatedOutOfScripted)
        |        }
        |      }
        |      val allManagedSources = (managedSources in Compile).value ++ (managedSources in Test).value
        |      copy(allManagedSources)
        |    }
        |  )
        |}""".stripMargin
    }

    private val scriptedTestContents = {
      """> show bloopConfigDir
        |# Some projects need to compile to generate resources. We do it now so that the configuration
        |# that we generate doesn't appear too old.
        |> resources
        |> registerDirectory
        |> installBloop
        |> copySources
        |> checkInstall
        |> copyContentOutOfScripted
      """.stripMargin
    }

    private val NewLine = System.lineSeparator
    def scriptedSettings(testDirectory: sbt.SettingKey[File]): Seq[Def.Setting[_]] = List(
      ScriptedKeys.scriptedBufferLog := true,
      ScriptedKeys.sbtTestDirectory := testDirectory.value,
      BuildKeys.scriptedAddSbtBloop := {
        import sbt.io.syntax.{fileToRichFile, singleFileFinder}
        val addSbtPlugin =
          s"""addSbtPlugin("${Keys.organization.value}" % "${Keys.name.value}" % "${Keys.version.value}")$NewLine"""
        val testPluginSrc = Keys.baseDirectory
          .in(sbt.ThisBuild)
          .value / "project" / "TestPlugin.scala"
        val tests = (ScriptedKeys.sbtTestDirectory.value / "projects").*(AllPassFilter).get
        tests.foreach { testDir =>
          IO.copyFile(testPluginSrc, testDir / "project" / "TestPlugin.scala")
          IO.createDirectory(testDir / "bloop-config")
          IO.write(testDir / "project" / "test-config.sbt", addSbtPlugin)
          IO.write(testDir / "project" / "TestPlugin.scala", testPluginContents)
          IO.write(testDir / "test-config.sbt", createScriptedSetup(testDir))
          IO.write(testDir / "test", scriptedTestContents)
        }
      },
    )
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
      |   *** An effort funded by the Scala Center Advisory Board ***
      |   ***********************************************************
    """.stripMargin
}
