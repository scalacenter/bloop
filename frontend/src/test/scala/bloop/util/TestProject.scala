package bloop.util

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import bloop.ScalaInstance
import bloop.bsp.ProjectUris
import bloop.config.Config
import bloop.config.Config.Platform
import bloop.data.JdkConfig
import bloop.io.{AbsolutePath, Paths, RelativePath}
import bloop.logging.{Logger, NoopLogger}
import bloop.util.TestUtil.ProjectArchetype
import bloop.config.ConfigCodecs

import ch.epfl.scala.bsp

import scala.tools.nsc.Properties
import scala.concurrent.ExecutionContext
import bloop.data.ClientInfo.CliClientInfo

final case class TestProject(
    config: Config.Project,
    deps: Option[List[TestProject]]
) {
  def baseDir: AbsolutePath = AbsolutePath(config.directory)
  def srcFor(relPath: String, exists: Boolean = true): AbsolutePath = {
    val sources = config.sources.map(AbsolutePath(_))
    if (exists) TestProject.srcFor(sources, relPath)
    else {
      val targetPath = RelativePath(relPath.stripPrefix(java.io.File.separator))
      val target = sources.head.resolve(targetPath)
      Files.createDirectories(target.getParent.underlying)
      target
    }
  }

  def clientClassesRootDir: AbsolutePath = {
    AbsolutePath(config.out.resolve("bloop-bsp-clients-classes"))
  }

  def externalClassesDir: AbsolutePath = {
    // Default on stable CLI directory, imitating [[ClientInfo.CliClientInfo]]
    val classesDir = config.classesDir
    val classesDirName = classesDir.getFileName()
    val cliSuffix = CliClientInfo.generateDirName(true)
    val projectDirName = s"$classesDirName-$cliSuffix"
    clientClassesRootDir.resolve(projectDirName)
  }

  def externalClassFileFor(relPath: String): AbsolutePath = {
    val classFile = externalClassesDir.resolve(RelativePath(relPath))
    if (classFile.exists) classFile
    else sys.error(s"Missing class file path ${relPath}")
  }

  lazy val bspId: bsp.BuildTargetIdentifier = {
    val uri = ProjectUris.toURI(AbsolutePath(config.directory), config.name)
    bsp.BuildTargetIdentifier(bsp.Uri(uri))
  }

  def rewriteProject(
      changeScala: Config.Scala => Config.Scala = identity[Config.Scala],
      changeCompileClasspath: List[Path] => List[Path] = identity[List[Path]],
      changeRuntimeClasspath: Option[List[Path]] => Option[List[Path]] =
        identity[Option[List[Path]]]
  ): TestProject = {
    val newScala = changeScala(config.scala.get)
    val newCompileClasspath = changeCompileClasspath(config.classpath)
    val newPlatform = config.platform.map {
      case jvm: Platform.Jvm =>
        jvm.copy(classpath = changeRuntimeClasspath(jvm.classpath))
      case other => other
    }
    val newConfig = config.copy(
      classpath = newCompileClasspath,
      platform = newPlatform,
      scala = Some(newScala)
    )
    new TestProject(newConfig, this.deps)
  }

  def toJson: String = {
    ConfigCodecs.toStr(
      Config.File.empty.copy(project = config)
    )
  }
}

object TestProject extends BaseTestProject

abstract class BaseTestProject {
  def apply(
      baseDir: AbsolutePath,
      name: String,
      sources: List[String],
      directDependencies: List[TestProject] = Nil,
      strictDependencies: Boolean = false,
      enableTests: Boolean = false,
      scalacOptions: List[String] = Nil,
      scalaOrg: Option[String] = None,
      scalaCompiler: Option[String] = None,
      scalaVersion: Option[String] = None,
      resources: List[String] = Nil,
      runtimeResources: Option[List[String]] = None,
      jvmConfig: Option[Config.JvmConfig] = None,
      runtimeJvmConfig: Option[Config.JvmConfig] = None,
      order: Config.CompileOrder = Config.Mixed,
      jars: Array[AbsolutePath] = Array(),
      sourcesGlobs: List[Config.SourcesGlobs] = Nil
  ): TestProject = {
    val projectBaseDir = Files.createDirectories(baseDir.underlying.resolve(name))
    val origin = TestUtil.syntheticOriginFor(baseDir)
    val ProjectArchetype(sourceDir, outDir, resourceDir, classes, runtimeResourceDir) =
      TestUtil.createProjectArchetype(baseDir.underlying, name)

    def classpathDeps(p: TestProject): List[AbsolutePath] = {
      val dir = AbsolutePath(p.config.classesDir)
      (dir :: p.deps.toList.flatten.flatMap(classpathDeps(_))).distinct
    }

    import bloop.engine.ExecutionContext.ioScheduler
    val version = scalaVersion.getOrElse(Properties.versionNumberString)

    val finalScalaOrg = scalaOrg.getOrElse("org.scala-lang")
    val finalScalaCompiler = scalaCompiler.getOrElse("scala-compiler")
    val instance =
      mkScalaInstance(finalScalaOrg, finalScalaCompiler, scalaVersion, jars.toList, NoopLogger)

    val allJars = instance.allJars.map(AbsolutePath.apply)
    val (compileClasspath, runtimeClasspath) = {
      val transitiveClasspath =
        (directDependencies.flatMap(classpathDeps) ++ allJars ++ jars).map(_.underlying)
      val directClasspath =
        (directDependencies.map(p => AbsolutePath(p.config.classesDir)) ++ allJars ++ jars)
          .map(_.underlying)
      if (strictDependencies) (directClasspath, transitiveClasspath)
      else (transitiveClasspath, transitiveClasspath)
    }
    val compileResourcesList = Some(List(resourceDir.underlying))
    val runtimeResourcesList = runtimeResources match {
      case None => compileResourcesList
      case Some(_) => Some(List(runtimeResourceDir.underlying))
    }
    val javaConfig = jvmConfig.getOrElse(JdkConfig.toConfig(JdkConfig.default))
    val setup = Config.CompileSetup.empty.copy(order = order)
    val scalaConfig = Config.Scala(
      finalScalaOrg,
      finalScalaCompiler,
      version,
      scalacOptions,
      allJars.map(_.underlying).toList,
      None,
      Some(setup)
    )

    val frameworks = if (enableTests) Config.TestFramework.DefaultFrameworks else Nil
    val testConfig = Config.Test(frameworks, Config.TestOptions.empty)
    val platform = Config.Platform.Jvm(
      javaConfig,
      None,
      runtimeJvmConfig,
      Some(runtimeClasspath),
      runtimeResourcesList
    )

    def toMap(xs: List[String]): Map[RelativePath, String] =
      xs.map(TestUtil.parseFile(_)).map(pf => pf.relativePath -> pf.contents).toMap

    TestUtil.writeFilesToBase(sourceDir, toMap(sources))
    TestUtil.writeFilesToBase(resourceDir, toMap(resources))
    runtimeResources.foreach(res => TestUtil.writeFilesToBase(runtimeResourceDir, toMap(res)))

    val config = Config.Project(
      name,
      projectBaseDir,
      Option(baseDir.underlying),
      List(sourceDir.underlying),
      if (sourcesGlobs.isEmpty) None
      else Some(sourcesGlobs),
      None,
      directDependencies.map(_.config.name),
      compileClasspath,
      outDir.underlying,
      classes.underlying,
      resources = compileResourcesList,
      scala = Some(scalaConfig),
      java = None,
      sbt = None,
      test = Some(testConfig),
      platform = Some(platform),
      resolution = None,
      tags = None
    )

    TestProject(config, Some(directDependencies))
  }

  protected def mkScalaInstance(
      scalaOrg: String,
      scalaName: String,
      scalaVersion: Option[String],
      allJars: Seq[AbsolutePath],
      logger: Logger
  )(implicit ec: ExecutionContext): ScalaInstance =
    scalaVersion
      .map(v => ScalaInstance.apply(scalaOrg, scalaName, v, allJars, logger))
      .getOrElse(TestUtil.scalaInstance)

  def populateWorkspaceInConfigDir(
      configDir: AbsolutePath,
      projects: List[TestProject]
  ): AbsolutePath = {
    Files.createDirectories(configDir.underlying)
    projects.foreach { project =>
      val configFile = configDir.resolve(s"${project.config.name}.json")
      Files.write(
        configFile.underlying,
        project.toJson.getBytes(StandardCharsets.UTF_8)
      )
    }
    configDir
  }

  def populateWorkspace(baseDir: AbsolutePath, projects: List[TestProject]): AbsolutePath = {
    val configDir = baseDir.resolve(".bloop")
    populateWorkspaceInConfigDir(configDir, projects)
  }

  def srcFor(sources: List[AbsolutePath], relPath: String): AbsolutePath = {
    import java.io.File
    val universalRelPath = relPath.stripPrefix("/").split('/').mkString(File.separator)
    val targetPath = RelativePath(universalRelPath)
    val rawFileName = targetPath.underlying.getFileName.toString
    if (rawFileName.endsWith(".scala") || rawFileName.endsWith(".java")) {
      val matchedPath = sources.foldLeft(None: Option[AbsolutePath]) {
        case (matched, base) =>
          if (matched.isDefined) matched
          else {
            val candidate = base.resolve(targetPath)
            if (candidate.exists) Some(candidate) else matched
          }
      }
      matchedPath.getOrElse(sys.error(s"Path ${targetPath} could not be found"))
    } else {
      sys.error(s"Source name in ${targetPath} does not end with '.scala' or '.java'")
    }
  }
}
