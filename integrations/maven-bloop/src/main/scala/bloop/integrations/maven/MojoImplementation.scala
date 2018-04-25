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
  private val testFrameworks: Array[Config.TestFramework] = Array(
    Config.TestFramework(List("org.scalacheck.ScalaCheckFramework")),
    Config.TestFramework(
      List("org.scalatest.tools.Framework", "org.scalatest.tools.ScalaTestFramework")),
    Config.TestFramework(
      List("org.specs.runner.SpecsFramework",
           "org.specs2.runner.Specs2Framework",
           "org.specs2.runner.SpecsFramework")),
    Config.TestFramework(List("com.novocode.junit.JUnitFramework"))
  )

  def writeCompileAndTestConfiguration(mojo: BloopMojo, log: Log): Unit = {
    import scala.collection.JavaConverters._
    val project = mojo.getProject()
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

    def writeConfig(sourceDirs0: Seq[File],
                    classesDir0: File,
                    classpath0: java.util.List[_],
                    launcher: AppLauncher,
                    configuration: String): Unit = {
      def abs(file: File): Path = file.toPath().toRealPath().toAbsolutePath()

      val name = project.getArtifactId()
      val build = project.getBuild()
      val baseDirectory = abs(project.getBasedir())
      val out = baseDirectory.resolve("target")
      val sourceDirs = sourceDirs0.map(abs).toArray
      val classesDir = abs(classesDir0)
      val classpath1 = {
        val cp = classpath0.asScala.asInstanceOf[List[String]].map(u => abs(new File(u)))
        if (cp.headOption.contains(classesDir)) cp.tail else cp
      }
      val classpath = classpath1.toArray
      val classpathOptions = mojo.getClasspathOptions()
      val dependencies = project.getProjectReferences().asScala.values.map(_.getArtifactId).toArray
      val allScalaJars = mojo.getAllScalaJars().map(abs).toArray
      val scalacArgs = mojo.getScalacArgs().asScala.toArray
      val platform = Config.Platform.default

      // FORMAT: OFF
      val config = {
        val test = Config.Test(testFrameworks, Config.TestOptions.empty)
        val java = Config.Java(mojo.getJavacArgs().asScala.toArray)
        val `scala` = Config.Scala(mojo.getScalaOrganization(), mojo.getScalaArtifactID(),
          mojo.getScalaVersion(), scalacArgs, allScalaJars)
        val jvm = Config.Jvm(Some(abs(mojo.getJavaHome())), launcher.getJvmArgs().toArray)

        val compileOptions = Config.CompileOptions(Config.Mixed)
        val project = Config.Project(name, baseDirectory, sourceDirs, dependencies, classpath, classpathOptions, compileOptions, out, classesDir, `scala`, jvm, java, test, platform)
        Config.File(Config.File.LatestVersion, project)
      }
      // FORMAT: ON

      val suffix = if (configuration == "compile") "" else s"-$configuration"
      val configTarget = new File(mojo.getBloopConfigDir, s"$name$suffix")
      log.info(s"Writing bloop configuration file to ${configTarget.getAbsolutePath()}")
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
}
