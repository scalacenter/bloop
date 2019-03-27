package bloop.util

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import bloop.ScalaInstance
import bloop.bsp.ProjectUris
import bloop.config.Config
import bloop.exec.JavaEnv
import bloop.io.{AbsolutePath, Paths, RelativePath}
import bloop.logging.NoopLogger
import bloop.util.TestUtil.ProjectArchetype
import ch.epfl.scala.bsp

import scala.tools.nsc.Properties

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

  def externalClassFileFor(relPath: String): AbsolutePath = {
    val classFile = AbsolutePath(config.classesDir).resolve(RelativePath(relPath))
    if (classFile.exists) classFile
    else sys.error(s"Missing class file path ${relPath}")
  }

  lazy val bspId: bsp.BuildTargetIdentifier = {
    val uri = ProjectUris.toURI(AbsolutePath(config.directory), config.name)
    bsp.BuildTargetIdentifier(bsp.Uri(uri))
  }

  def toJson: String = {
    bloop.config.toStr(
      Config.File.empty.copy(project = config)
    )
  }
}

object TestProject {
  def apply(
      baseDir: AbsolutePath,
      name: String,
      sources: List[String],
      directDependencies: List[TestProject] = Nil,
      enableTests: Boolean = false,
      scalacOptions: List[String] = Nil,
      scalaVersion: Option[String] = None,
      resources: List[String] = Nil,
      jvmConfig: Option[Config.JvmConfig] = None,
      order: Config.CompileOrder = Config.Mixed,
      jars: Array[AbsolutePath] = Array()
  ): TestProject = {
    val projectBaseDir = Files.createDirectories(baseDir.underlying.resolve(name))
    val origin = TestUtil.syntheticOriginFor(baseDir)
    val ProjectArchetype(sourceDir, outDir, resourceDir, classes) =
      TestUtil.createProjectArchetype(baseDir.underlying, name)

    def classpathDeps(p: TestProject): List[AbsolutePath] = {
      val dir = AbsolutePath(p.config.classesDir)
      (dir :: p.deps.toList.flatten.flatMap(classpathDeps(_))).distinct
    }

    import bloop.engine.ExecutionContext.ioScheduler
    val version = scalaVersion.getOrElse(Properties.versionNumberString)
    val instance = scalaVersion
      .map(v => ScalaInstance.apply("org.scala-lang", "scala-compiler", v, jars.toList, NoopLogger))
      .getOrElse(TestUtil.scalaInstance)

    val allJars = instance.allJars.map(AbsolutePath.apply)
    val depsTargets = directDependencies.flatMap(d => classpathDeps(d))
    val classpath = (depsTargets ++ allJars ++ jars).map(_.underlying)
    val javaConfig = jvmConfig.getOrElse(JavaEnv.toConfig(JavaEnv.default))
    val javaEnv = JavaEnv.fromConfig(javaConfig)
    val setup = Config.CompileSetup.empty.copy(order = order)
    val scalaConfig = Config.Scala(
      "org.scala-lang",
      "scala-compiler",
      version,
      scalacOptions,
      allJars.map(_.underlying).toList,
      None,
      Some(setup)
    )

    val frameworks = if (enableTests) Config.TestFramework.DefaultFrameworks else Nil
    val testConfig = Config.Test(frameworks, Config.TestOptions.empty)
    val platform = Config.Platform.Jvm(javaConfig, None)

    def toMap(xs: List[String]): Map[RelativePath, String] =
      xs.map(TestUtil.parseFile(_)).map(pf => pf.relativePath -> pf.contents).toMap

    TestUtil.writeFilesToBase(sourceDir, toMap(sources))
    TestUtil.writeFilesToBase(resourceDir, toMap(resources))

    val config = Config.Project(
      name,
      projectBaseDir,
      List(sourceDir.underlying),
      directDependencies.map(_.config.name),
      classpath,
      outDir.underlying,
      classes.underlying,
      resources = Some(List(resourceDir.underlying)),
      scala = Some(scalaConfig),
      java = None,
      sbt = None,
      test = Some(testConfig),
      platform = Some(platform),
      resolution = None
    )

    TestProject(config, Some(directDependencies))
  }

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
    val targetPath = RelativePath(relPath.stripPrefix(java.io.File.separator))
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
