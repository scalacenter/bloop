package bloop.integrations.maven

import java.io.File
import java.nio.file.Files

import bloop.integrations.BloopConfig
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.{MavenPluginManager, Mojo, MojoExecution}
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration
import org.codehaus.plexus.util.xml.Xpp3Dom

object BloopIntegration {
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

  def writeCompileAndTestConfiguration(mojo: BloopMojo, log: Log): Unit = {
    val configDir = mojo.bloopConfigDir.toPath()
    if (!Files.exists(configDir)) Files.createDirectory(configDir)

    def writeConfig(sourceDir: File, classesDir: File, configuration: String): Unit = {
      import mojo.project
      import scala.collection.JavaConverters._

      val name = project.getArtifactId()
      val build = project.getBuild()
      val baseDirectory = project.getBasedir()
      val classpath = Nil
      val dependencies = Nil
      val testFrameworks = Nil
      val allScalaJars = Nil
      val tmpDir = new File(classesDir, "tmp-bloop")
      val fork = false
      val javaOptions = Nil
      val javaHome = new File(classesDir, "fake-java-home")
      val compileConfig = BloopConfig(
        name,
        baseDirectory,
        dependencies,
        mojo.scalaOrganization,
        mojo.scalaArtifactID,
        mojo.scalaVersion,
        classpath,
        classesDir,
        mojo.args.toList,
        mojo.javacArgs.toList,
        List(sourceDir),
        testFrameworks,
        fork,
        javaHome,
        javaOptions,
        allScalaJars,
        tmpDir
      )

      val suffix = if (configuration == "compile") "" else s"-$configuration"
      val configTarget = new File(mojo.bloopConfigDir, s"$name$suffix")
      log.info(s"Writing bloop configuration file to ${configTarget.getAbsolutePath()}")
      compileConfig.writeTo(configTarget)
    }

    writeConfig(mojo.sourceDir, mojo.outputDir, "compile")
    writeConfig(mojo.testSourceDir, mojo.testOutputDir, "test")
  }
}
