package bloop.integrations.maven

import java.io.File
import java.nio.file.Files

import bloop.integrations.BloopConfig
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
  private val testFrameworks: List[List[String]] = List(
    List("org.scalacheck.ScalaCheckFramework"),
    List("org.scalatest.tools.Framework", "org.scalatest.tools.ScalaTestFramework"),
    List("org.specs.runner.SpecsFramework",
         "org.specs2.runner.Specs2Framework",
         "org.specs2.runner.SpecsFramework"),
    List("com.novocode.junit.JUnitFramework")
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
      val name = project.getArtifactId()
      val build = project.getBuild()
      val baseDirectory = project.getBasedir()
      val sourceDirs = sourceDirs0.map(_.getCanonicalFile())
      val classesDir = classesDir0.getCanonicalFile()
      val classpath = {
        val cp = classpath0.asScala.toList.asInstanceOf[List[String]].map(new File(_))
        if (cp.headOption.contains(classesDir)) cp.tail else cp
      }
      val classpathOptions = mojo.getClasspathOptions()
      val dependencies = project.getProjectReferences().asScala.values.map(_.getArtifactId).toList
      val allScalaJars = mojo.getAllScalaJars()
      val tmpDir = new File(classesDir, "tmp-bloop")
      val javaOptions = launcher.getJvmArgs()
      val javaHome = mojo.getJavaHome()

      // FORMAT: OFF
      val config = BloopConfig(name, baseDirectory, dependencies, mojo.getScalaOrganization,
        mojo.getScalaArtifactID, mojo.getScalaVersion, classpath, classpathOptions, classesDir,
        mojo.getScalacArgs.asScala,  mojo.getJavacArgs().asScala, sourceDirs, testFrameworks,
        javaHome, javaOptions, allScalaJars, tmpDir
      )
      // FORMAT: ON

      val suffix = if (configuration == "compile") "" else s"-$configuration"
      val configTarget = new File(mojo.getBloopConfigDir, s"$name$suffix")
      log.info(s"Writing bloop configuration file to ${configTarget.getAbsolutePath()}")
      config.writeTo(configTarget)
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
