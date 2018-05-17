package bloop.integrations.maven

import java.io.File
import java.nio.file.{Files, Path}

import bloop.config.Config
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.{MavenPluginManager, Mojo, MojoExecution}
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import scala_maven.AppLauncher

object MojoImplementation {
  private val ScalaMavenGroupArtifact = "net.alchim31.maven:scala-maven-plugin"

  def initializeMojo(project: MavenProject,
                     session: MavenSession,
                     mojoExecution: MojoExecution,
                     mavenPluginManager: MavenPluginManager,
                     encoding: String): BloopMojo = {
    val currentConfig = mojoExecution.getConfiguration()
    val scalaMavenPlugin = Option(project.getBuild().getPluginsAsMap().get(ScalaMavenGroupArtifact))
      .getOrElse(sys.error(s"The plugin $ScalaMavenGroupArtifact could not be found."))
    val scalaMavenConfig = scalaMavenPlugin.getConfiguration().asInstanceOf[Xpp3Dom]
    mojoExecution.setConfiguration(Xpp3Dom.mergeXpp3Dom(currentConfig, scalaMavenConfig))
    mavenPluginManager
      .getConfiguredMojo(classOf[Mojo], session, mojoExecution)
      .asInstanceOf[BloopMojo]
  }

  private val emptyLauncher = new AppLauncher("", "", Array(), Array())

  val ScalaCheckFramework = Config.TestFramework(List(
    "org.scalacheck.ScalaCheckFramework"
  ))
  val ScalaTestFramework = Config.TestFramework(List(
    "org.scalatest.tools.Framework",
    "org.scalatest.tools.ScalaTestFramework"
  ))
  val SpecsFramework = Config.TestFramework(List(
    "org.specs.runner.SpecsFramework",
    "org.specs2.runner.Specs2Framework",
    "org.specs2.runner.SpecsFramework"
  ))
  val JUnitFramework = Config.TestFramework(List(
    "com.novocode.junit.JUnitFramework"
  ))

  private val testFrameworks: Array[Config.TestFramework] = Array(
    ScalaCheckFramework,
    ScalaTestFramework,
    SpecsFramework,
    JUnitFramework
  )

  private val DefaultTestOptions =
    Config.TestOptions(Nil, List(Config.TestArgument(Array("-v", "-a"), Some(JUnitFramework))))

  def writeCompileAndTestConfiguration(mojo: BloopMojo, session: MavenSession, log: Log): Unit = {
    import scala.collection.JavaConverters._
    def abs(file: File): Path = file.toPath().toRealPath().toAbsolutePath()

    val root = new File(session.getExecutionRootDirectory())
    val project = mojo.getProject()
    val dependencies =
      session.getProjectDependencyGraph.getUpstreamProjects(project, true).asScala.toList
    val dependencyNames = dependencies.map(_.getArtifactId()).toArray

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
          log.warn(s"Using empty launcher: launcher id '${launcherId}' doesn't exist.")
        emptyLauncher
      }

    val classpathOptions = mojo.getClasspathOptions()
    val allScalaJars = mojo.getAllScalaJars().map(abs).toArray
    val scalacArgs = mojo.getScalacArgs().asScala.toArray

    def writeConfig(sourceDirs0: Seq[File],
                    classesDir0: File,
                    classpath0: java.util.List[_],
                    launcher: AppLauncher,
                    configuration: String): Unit = {

      val suffix = if (configuration == "compile") "" else s"-$configuration"
      val name = project.getArtifactId() + suffix
      val build = project.getBuild()
      val baseDirectory = abs(project.getBasedir())
      val out = baseDirectory.resolve("target")
      val sourceDirs = sourceDirs0.map(abs).toArray
      val classesDir = abs(classesDir0)
      val classpath = {
        val projectDependencies = dependencies.flatMap { d =>
          val build = d.getBuild()
          if (configuration == "compile") build.getOutputDirectory() :: Nil
          else build.getTestOutputDirectory() :: build.getOutputDirectory() :: Nil
        }

        val cp = classpath0.asScala.toList.asInstanceOf[List[String]].map(u => abs(new File(u)))
        (projectDependencies.map(u => abs(new File(u))) ++ cp).toArray
      }

      // FORMAT: OFF
      val config = {
        val test = Config.Test(testFrameworks, DefaultTestOptions)
        val java = Config.Java(mojo.getJavacArgs().asScala.toArray)
        val `scala` = Config.Scala(mojo.getScalaOrganization(), mojo.getScalaArtifactID(),
          mojo.getScalaVersion(), scalacArgs, allScalaJars)
        val jvm = Config.Jvm(Some(abs(mojo.getJavaHome().getParentFile.getParentFile)), launcher.getJvmArgs().toArray)
        val compileOptions = Config.CompileOptions(Config.Mixed)
        val project = Config.Project(name, baseDirectory, sourceDirs, dependencyNames, classpath, classpathOptions, compileOptions, out, classesDir, `scala`, jvm, java, test)
        Config.File(Config.File.LatestVersion, project)
      }
      // FORMAT: ON

      val configTarget = new File(mojo.getBloopConfigDir, s"$name.json")
      val finalTarget = relativize(root, configTarget).getOrElse(configTarget.getAbsolutePath)
      log.info(s"Generated $finalTarget")
      Config.File.write(config, configTarget.toPath)
    }

    writeConfig(mojo.getCompileSourceDirectories.asScala,
                mojo.getCompileOutputDir,
                project.getCompileClasspathElements(),
                launcher,
                "compile")
    writeConfig(mojo.getTestSourceDirectories.asScala,
                mojo.getTestOutputDir,
                project.getTestClasspathElements(),
                launcher,
                "test")
  }

  private def relativize(base: File, file: File): Option[String] = {
    import scala.util.control.Exception.catching
    val basePath = (if (base.isAbsolute) base else base.getCanonicalFile).toPath
    val filePath = (if (file.isAbsolute) file else file.getCanonicalFile).toPath
    if ((filePath startsWith basePath) || (filePath.normalize() startsWith basePath.normalize())) {
      val relativePath = catching(classOf[IllegalArgumentException]) opt (basePath relativize filePath)
      relativePath map (_.toString)
    } else None
  }
}
