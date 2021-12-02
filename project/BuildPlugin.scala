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
  val buildBase = Keys.baseDirectory in ThisBuild

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
    nailgunClientLocation := buildBase.value / "nailgun" / "pynailgun" / "ng.py"
  )

  import sbt.Compile

  import sbtbuildinfo.{BuildInfoKey, BuildInfoKeys}
  final val BloopBackendInfoKeys: List[BuildInfoKey] = {
    val scalaJarsKey =
      BuildInfoKey.map(Keys.scalaInstance) { case (_, i) => "scalaJars" -> i.allJars.toList }
    List(Keys.scalaVersion, Keys.scalaOrganization, scalaJarsKey)
  }

  import sbt.util.Logger.{Null => NullLogger}
  def bloopInfoKeys(
      nativeBridge04: Reference,
      jsBridge06: Reference,
      jsBridge1: Reference
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
    val jsBridge10Key = fromIvyModule("jsBridge1", Keys.ivyModule in jsBridge1)
    val nativeBridge04Key = fromIvyModule("nativeBridge04", Keys.ivyModule in nativeBridge04)
    val bspKey = BuildInfoKey.constant("bspVersion" -> Dependencies.bspVersion)
    val extra = List(
      zincKey,
      developersKey,
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
    BuildKeys.schemaVersion := "4.2-refresh-3"
  )

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
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
  )

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions =
    "-Xmx3g" :: "-Xms1g" :: "-XX:ReservedCodeCacheSize=512m" :: "-XX:MaxInlineLevel=20" :: Nil

  object BuildDefaults {

    import sbtbuildinfo.BuildInfoPlugin.{autoImport => BuildInfoKeys}

    val frontendTestBuildSettings: Seq[Def.Setting[_]] = {
      sbtbuildinfo.BuildInfoPlugin.buildInfoScopedSettings(Test) ++ List(
        BuildInfoKeys.buildInfoKeys in Test := {
          import sbtbuildinfo.BuildInfoKey
          val junitTestJars = BuildInfoKey.map(Keys.externalDependencyClasspath in Test) {
            case (_, classpath) =>
              val jars = classpath.map(_.data.getAbsolutePath)
              val junitJars = jars.filter(j => j.contains("junit") || j.contains("hamcrest"))
              "junitTestJars" -> junitJars
          }

          List(junitTestJars, Keys.baseDirectory in ThisBuild)
        },
        BuildInfoKeys.buildInfoPackage in Test := "bloop.internal.build",
        BuildInfoKeys.buildInfoObject in Test := "BuildTestInfo"
      )
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
