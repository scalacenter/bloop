package bloop.integrations.maven

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.JavaConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import bloop.config.Config
import bloop.config.Tag

import org.apache.maven.artifact.Artifact
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Resource
import org.apache.maven.plugin.MavenPluginManager
import org.apache.maven.plugin.Mojo
import org.apache.maven.plugin.MojoExecution
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.ArtifactRequest
import scala_maven.AppLauncher

object MojoImplementation {
  private val ScalaMavenGroupArtifact = "net.alchim31.maven:scala-maven-plugin"
  private val JavaMavenGroupArtifact = "org.apache.maven.plugins:maven-compiler-plugin"

  def initializeMojo(
      project: MavenProject,
      session: MavenSession,
      mojoExecution: MojoExecution,
      mavenPluginManager: MavenPluginManager,
      encoding: String
  ): Either[String, BloopMojo] = {
    val buildPlugins = project.getBuild().getPluginsAsMap();

    val (newConfig, moduleType) = Option(buildPlugins.get(ScalaMavenGroupArtifact)) match {
      case None => (None, BloopMojo.ModuleType.JAVA)
      case Some(scalaMavenPlugin) =>
        (Some(scalaMavenPlugin.getConfiguration.asInstanceOf[Xpp3Dom]), BloopMojo.ModuleType.SCALA)
    }

    val javaCompilerArgs: List[String] = Option(buildPlugins.get(JavaMavenGroupArtifact)) match {
      case None => List()
      case Some(javaMavenPlugin) =>
        val javaConfig = javaMavenPlugin.getConfiguration.asInstanceOf[Xpp3Dom]
        if (javaConfig != null) {
          val compilerArgs = Option(javaConfig.getChild("compilerArgs"))
          compilerArgs.map(_.getChildren.map(_.getValue).toList).getOrElse(Nil)
        } else List()
    }

    val currentConfig = mojoExecution.getConfiguration
    val dom = newConfig.map(nc => Xpp3Dom.mergeXpp3Dom(currentConfig, nc))
    Try {
      dom.foreach(mojoExecution.setConfiguration)
      val mojo = mavenPluginManager
        .getConfiguredMojo(classOf[Mojo], session, mojoExecution)
        .asInstanceOf[BloopMojo]
      mojo.setModuleType(moduleType)
      mojo.setJavaCompilerArgs(javaCompilerArgs.asJava)
      mojo
    } match {
      case Success(value) => Right(value)
      case Failure(e) => Left(s"Failed to init BloopMojo with conf:\n$dom\n${e.getMessage}")
    }
  }

  private val emptyLauncher = new AppLauncher("", "", Array(), Array())

  def writeCompileAndTestConfiguration(mojo: BloopMojo, session: MavenSession, log: Log): Unit = {
    import scala.collection.JavaConverters._
    def abs(file: File): Path = {
      file.mkdirs()
      file.toPath().toRealPath().toAbsolutePath()
    }

    def resolveArtifact(artifact: Artifact, classifierOpt: Option[String] = None): Option[File] =
      try {
        val classifier = classifierOpt.orElse(Option(artifact.getClassifier())).getOrElse("")
        val suffix = if (classifier.nonEmpty) s":$classifier" else ""
        log.info("Resolving artifact: " + artifact + suffix)
        val request = new ArtifactRequest()
        request.setArtifact(
          new DefaultArtifact(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            classifier,
            artifact.getType(),
            artifact.getVersion()
          )
        )
        request.setRepositories(mojo.getRemoteRepositories())
        val result = mojo.getRepoSystem().resolveArtifact(session.getRepositorySession(), request)
        log.info("SUCCESS " + artifact)
        Some(result.getArtifact().getFile())

      } catch {
        case t: Throwable =>
          log.error("FAILURE " + artifact, t)
          None
      }

    val reactorProjectsSet = mojo
      .getReactorProjects()
      .asScala
      .map { project =>
        (project.getGroupId(), project.getArtifactId(), project.getVersion())
      }
      .toSet

    def isNotReactorProjectArtifact(artifact: Artifact) = {
      !reactorProjectsSet((artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()))
    }

    val root = new File(session.getExecutionRootDirectory())
    val project = mojo.getProject()
    val dependencies =
      session.getProjectDependencyGraph.getUpstreamProjects(project, true).asScala.toList
    val dependencyNames = dependencies.map(_.getArtifactId()).toList

    val configDir = mojo.getBloopConfigDir.toPath()
    if (!Files.exists(configDir)) Files.createDirectory(configDir)

    val launcherId = mojo.getLauncher()
    val launchers = mojo.getLaunchers()
    val launcher = launchers
      .find(_.getId == launcherId)
      .getOrElse {
        if (launchers.isEmpty)
          log.info(s"Using empty launcher: no run setup for ${project.getName}.")
        else if (launcherId.nonEmpty)
          log.warn(s"Using empty launcher: Launcher ID '${launcherId}' does not exist")
        emptyLauncher
      }

    // check if Scala is contained in this project
    // findScalaContext throws an exception if it can't find Scala
    val scalaContext = Try(mojo.findScalaContext()).toOption
    val compileSetup = mojo.getCompileSetup()
    val compilerAndDeps = scalaContext.toList.flatMap(_.findCompilerAndDependencies().asScala)
    val allScalaJars = compilerAndDeps.map { artifact =>
      artifact.getFile().toPath()
    }.toList

    val scalaOrganization = compilerAndDeps
      .collectFirst {
        case artifact
            if artifact.getArtifactId() == "scala3-compiler_3" || artifact
              .getArtifactId() == "scala-compiler" =>
          artifact.getGroupId()
      }
      .getOrElse("org.scala-lang")
    val scalacArgs = mojo.getScalacArgs().asScala.toList.filter(_ != null)

    def writeConfig(
        sourceDirs0: Seq[File],
        classesDir0: File,
        // needs to be lazy, since we resolve artifacts later on
        classpath0: () => java.util.List[_],
        resources0: java.util.List[_],
        launcher: AppLauncher,
        configuration: String
    ): Unit = {
      val suffix = if (configuration == "compile") "" else s"-$configuration"
      val name = project.getArtifactId() + suffix
      val build = project.getBuild()
      val baseDirectory = abs(project.getBasedir())
      val out = baseDirectory.resolve("target")
      val analysisOut = None
      val sourceDirs = sourceDirs0.map(abs).toList
      val classesDir = abs(classesDir0)
      val artifacts = project.getArtifacts().asScala
      lazy val libraryAndDependencies = scalaContext.toList
        .flatMap(_.findLibraryAndDependencies().asScala)

      // if we don't add scala-library explicitely it will not be available in artifacts
      val hasScalaLibrary = artifacts.exists {
        case a: Artifact => a.getArtifactId() == "scala-library"
      }
      val allArtifacts = if (hasScalaLibrary) artifacts else artifacts ++ libraryAndDependencies
      val modules =
        allArtifacts.collect {
          case art: Artifact if art.getType() == "jar" && isNotReactorProjectArtifact(art) =>
            if (art.getArtifactId() == "scala-library")
              scalaContext match {
                case Some(context) =>
                  /* If the scala library is not specified explicitely as recommended
                   * it might sometimes be wrong, this doesn't happen for Scala 3.
                   */
                  val scalaVersion = context.version().toString()
                  if (!scalaVersion.startsWith("3.") && scalaVersion != art.getVersion())
                    art.setVersion(context.version.toString)
                case _ =>
              }
            resolveArtifact(art).foreach { resolvedFile =>
              //since we don't resolve dependencies automatically in the plugin, this will be null
              art.setFile(resolvedFile)
            }
            if (mojo.shouldDownloadSources()) {
              resolveArtifact(art, Some("sources"))
            }
            artifactToConfigModule(art, project, session)
        }
      val resolution = Some(Config.Resolution(modules.toList))

      val classpath = {
        val projectDependencies = dependencies.flatMap { d =>
          val build = d.getBuild()
          if (configuration == "compile") build.getOutputDirectory() :: Nil
          else build.getTestOutputDirectory() :: build.getOutputDirectory() :: Nil
        }

        val cp = classpath0().asScala.toList.asInstanceOf[List[String]].map(u => abs(new File(u)))
        // scalaLibrary might not be added by default to classpath and it's needed for the compilation
        val hasScalaLibrary = cp.exists(p => p.toFile().getName().contains("scala_library-"))

        val fullClasspath =
          if (hasScalaLibrary) cp else cp ++ libraryAndDependencies.map(_.getFile().toPath())
        (projectDependencies.map(u => abs(new File(u))) ++ fullClasspath).toList
      }

      val tags = if (configuration == "test") List(Tag.Test) else List(Tag.Library)

      // add main dependency to test project
      val fullDependencies =
        if (configuration == "test") project.getArtifactId :: dependencyNames else dependencyNames

      // FORMAT: OFF
      val config = {
        val sbt = None
        val test = Some(Config.Test.defaultConfiguration)
        val java = Some(Config.Java(mojo.getJavacArgs().asScala.toList))
        val `scala` =
          scalaContext.map{
            context =>
              Config.Scala(scalaOrganization, mojo.getScalaArtifactID(), context.version().toString(), scalacArgs, allScalaJars, analysisOut, Some(compileSetup))
          }
        val javaHome = Some(abs(mojo.getJavaHome().getParentFile.getParentFile))
        val mainClass = if (launcher.getMainClass().isEmpty) None else Some(launcher.getMainClass())
        val platform = Some(Config.Platform.Jvm(Config.JvmConfig(javaHome, launcher.getJvmArgs().toList), mainClass, None, None, None))
        val resources = Some(resources0.asScala.toList.flatMap{
          case a: Resource => Option(Paths.get(a.getDirectory()))
          case _ => None
        })
        val project = Config.Project(name, baseDirectory, Some(root.toPath), sourceDirs, None, None, fullDependencies, classpath, out, classesDir, resources, `scala`, java, sbt, test, platform, resolution, Some(tags))
        Config.File(Config.File.LatestVersion, project)
      }
      // FORMAT: ON

      val configTarget = new File(mojo.getBloopConfigDir, s"$name.json")
      val finalTarget = relativize(root, configTarget).getOrElse(configTarget.getAbsolutePath)
      log.info(s"Generated $finalTarget")
      log.debug(s"Configuration to be serialized:\n$config")
      bloop.config.write(config, configTarget.toPath)
    }

    writeConfig(
      mojo.getCompileSourceDirectories.asScala.toSeq,
      mojo.getCompileOutputDir,
      project.getCompileClasspathElements,
      project.getResources,
      launcher,
      "compile"
    )

    writeConfig(
      mojo.getTestSourceDirectories.asScala.toSeq,
      mojo.getTestOutputDir,
      project.getTestClasspathElements,
      project.getTestResources,
      launcher,
      "test"
    )
  }

  private def relativize(base: File, file: File): Option[String] = {
    import scala.util.control.Exception.catching
    val basePath = (if (base.isAbsolute) base else base.getCanonicalFile).toPath
    val filePath = (if (file.isAbsolute) file else file.getCanonicalFile).toPath
    if ((filePath startsWith basePath) || (filePath.normalize() startsWith basePath.normalize())) {
      val relativePath =
        catching(classOf[IllegalArgumentException]) opt (basePath relativize filePath)
      relativePath map (_.toString)
    } else None
  }

  private def artifactToConfigModule(
      artifact: Artifact,
      project: MavenProject,
      session: MavenSession
  ): Config.Module = {
    val base = session.getLocalRepository().getBasedir()
    val artifactRelativePath = session.getLocalRepository().pathOf(artifact)
    val sources = artifactRelativePath.replace(".jar", "-sources.jar")
    val sourcesJarPath = Paths.get(base).resolve(sources)
    val sourcesList = if (sourcesJarPath.toFile().exists()) {
      List(
        Config.Artifact(
          name = artifact.getArtifactId(),
          classifier = Option("sources"),
          checksum = None,
          path = sourcesJarPath
        )
      )
    } else {
      Nil
    }
    if (artifact.getFile() == null)
      throw new IllegalArgumentException(s"Could not resolve $artifact")
    Config.Module(
      organization = artifact.getGroupId(),
      name = artifact.getArtifactId(),
      version = artifact.getVersion(),
      configurations = None,
      Config.Artifact(
        name = artifact.getArtifactId(),
        classifier = Option(artifact.getClassifier()),
        checksum = None,
        path = artifact.getFile().toPath()
      ) :: sourcesList
    )
  }
}
