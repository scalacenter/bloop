package build

import java.io.File

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
import sbt.io.syntax.fileToRichFile
import sbt.librarymanagement.syntax.stringToOrganization
import sbt.util.FileFunction
import sbtdynver.GitDescribeOutput
import sbtbuildinfo.BuildInfoPlugin.{autoImport => BuildInfoKeys}

object BuildPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  import sbt.plugins.IvyPlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    JvmPlugin && IvyPlugin
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

  import sbt.{Test, TestFrameworks, Tests}
  val buildBase = (ThisBuild / Keys.baseDirectory)

  val bloopName = Def.settingKey[String]("The name to use in build info generated code")
  val nailgunClientLocation = Def.settingKey[sbt.File]("Where to find the python nailgun client")

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
    nailgunClientLocation := buildBase.value / "frontend" / "src" / "test" / "resources" / "pynailgun" / "ng.py"
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
    (Test / Keys.testOptions) += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    (Test / Keys.publishArtifact) := false
  )

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "io.github.alexarchambault.bleep",
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := Dependencies.Scala212Version,
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

  import sbt.CrossVersion

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

  object BuildDefaults {

    val frontendTestBuildSettings: Seq[Def.Setting[_]] = {
      sbtbuildinfo.BuildInfoPlugin.buildInfoScopedSettings(Test) ++ List(
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
            junitTestJars,
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
