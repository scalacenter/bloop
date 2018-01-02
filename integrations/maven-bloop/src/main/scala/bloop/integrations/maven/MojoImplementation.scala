package bloop.integrations.maven

import java.io.File
import java.nio.file.Files

import bloop.integrations.BloopConfig
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.{MavenPluginManager, Mojo, MojoExecution}
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

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

  def writeCompileAndTestConfiguration(mojo: BloopMojo, log: Log): Unit = {
    import scala.collection.JavaConverters._
    val project = mojo.getProject()
    val configDir = mojo.getBloopConfigDir.toPath()
    if (!Files.exists(configDir)) Files.createDirectory(configDir)

    def writeConfig(sourceDirs0: Seq[File],
                    classesDir0: File,
                    classpath0: java.util.List[_],
                    configuration: String): Unit = {
      val name = project.getArtifactId()
      val build = project.getBuild()
      val baseDirectory = project.getBasedir()
      val sourceDirs = sourceDirs0.map(_.getCanonicalFile())
      val classesDir = classesDir0.getCanonicalFile()
      val classpath = {
        val elements = classpath0.asScala.toList
        println(project.getModel().getPackaging)
        val cp = project.getCompileClasspathElements().asScala.map(new File(_))
        if (cp.headOption.contains(classesDir)) cp.tail else cp
      }
      val dependencies = project.getProjectReferences().asScala.values.map(_.getArtifactId).toList
      val testFrameworks = Nil
      val allScalaJars = Nil
      val tmpDir = new File(classesDir, "tmp-bloop")
      val fork = false
      val javaOptions = Nil
      val javaHome = mojo.getJavaHome()

      // FORMAT: OFF
      val compileConfig = BloopConfig(name, baseDirectory, dependencies, mojo.getScalaOrganization,
        mojo.getScalaArtifactID, mojo.getScalaVersion, classpath, classesDir, mojo.getScalacArgs.asScala,
        mojo.getJavacArgs().asScala, sourceDirs, testFrameworks, fork, javaHome, javaOptions, allScalaJars, tmpDir
      )
      // FORMAT: ON

      val suffix = if (configuration == "compile") "" else s"-$configuration"
      val configTarget = new File(mojo.getBloopConfigDir, s"$name$suffix")
      log.info(s"Writing bloop configuration file to ${configTarget.getAbsolutePath()}")
      compileConfig.writeTo(configTarget)
    }

    writeConfig(mojo.getCompileSourceDirectories.asScala,
                mojo.getCompileOutputDir,
                project.getCompileClasspathElements(),
                "compile")
    writeConfig(mojo.getTestSourceDirectories.asScala,
                mojo.getTestOutputDir,
                project.getTestClasspathElements(),
                "test")
  }
}
