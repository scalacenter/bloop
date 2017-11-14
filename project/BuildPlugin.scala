package build

import sbt.{AutoPlugin, Def, Keys, PluginTrigger, Plugins}

object BuildPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin
  import com.lucidchart.sbt.scalafmt.ScalafmtCorePlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    JvmPlugin && ScalafmtCorePlugin && ReleaseEarlyPlugin
  val autoImport = BuildKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    BuildImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    BuildImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    BuildImplementation.projectSettings
}

object BuildKeys {
  import sbt.{Reference, RootProject, ProjectRef, BuildRef, file}
  import sbt.librarymanagement.syntax.stringToOrganization
  final val testDependencies = List(
    "junit"        % "junit"           % "4.12" % "test",
    "com.novocode" % "junit-interface" % "0.11" % "test"
  )

  def inProject(ref: Reference)(ss: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] =
    sbt.inScope(sbt.ThisScope.in(project = ref))(ss)

  def inProjectRefs(refs: Seq[Reference])(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    refs.flatMap(inProject(_)(ss))

  def inCompileAndTest(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    Seq(sbt.Compile, sbt.Test).flatMap(sbt.inConfig(_)(ss))

  // Use absolute paths so that references work even if `ThisBuild` changes
  final val AbsolutePath = file(".").getCanonicalFile.getAbsolutePath

  final val ZincProject = RootProject(file(s"$AbsolutePath/zinc"))
  final val Zinc        = ProjectRef(ZincProject.build, "zinc")
  final val ZincRoot    = ProjectRef(ZincProject.build, "zincRoot")
  final val ZincBridge  = ProjectRef(ZincProject.build, "compilerBridge")

  final val NailgunProject  = RootProject(file(s"$AbsolutePath/nailgun"))
  final val NailgunBuild    = BuildRef(NailgunProject.build)
  final val Nailgun         = ProjectRef(NailgunProject.build, "nailgun")
  final val NailgunServer   = ProjectRef(NailgunProject.build, "nailgun-server")
  final val NailgunExamples = ProjectRef(NailgunProject.build, "nailgun-examples")
}

object BuildImplementation {
  import sbt.{url, file}
  import sbt.{Developer, Resolver, Watched, Compile, Test}

  // This should be added to upstream sbt.
  def GitHub(org: String, project: String): java.net.URL =
    url(s"https://github.com/$org/$project")
  def GitHubDev(handle: String, fullName: String, email: String) =
    Developer(handle, fullName, email, url(s"https://github.com/$handle"))

  import com.typesafe.sbt.SbtPgp.autoImport.PgpKeys
  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin.{autoImport => ReleaseEarlyKeys}

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val publishSettings: Seq[Def.Setting[_]] = Seq(
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(ThisRepo),
    Keys.publishArtifact in Test := false,
    Keys.licenses := Seq("BSD" -> url("http://opensource.org/licenses/BSD-3-Clause")),
    Keys.developers := List(
      GitHubDev("Duhemm", "Martin Duhem", "martin.duhem@gmail.com"),
      GitHubDev("jvican", "Jorge Vicente Cantero", "jorge@vican.me")
    ),
    PgpKeys.pgpPublicRing := file("/drone/.gnupg/pubring.asc"),
    PgpKeys.pgpSecretRing := file("/drone/.gnupg/secring.asc"),
    ReleaseEarlyKeys.releaseEarlyWith := ReleaseEarlyKeys.SonatypePublisher
  )

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    Keys.commands += Semanticdb.command(Keys.crossScalaVersions.value),
  )

  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.resolvers += Resolver.jcenterRepo,
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := "2.12.4",
    Keys.triggeredMessage := Watched.clearWhenTriggered,
  ) ++ publishSettings

  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    Keys.scalacOptions in Compile := reasonableCompileOptions,
    Keys.publishArtifact in Compile in Keys.packageDoc := false
  )

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: "-Xlint" :: Nil
  )
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
      |   ***           Welcome to the build of `bloop`           ***
      |   *** An effort funded by the Scala Center Advisory Board ***
      |   ***********************************************************
    """.stripMargin
}
