package bloop.integrations.maven;

import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import scala_maven.ScalaCompileMojo;

import java.io.File;
import java.util.List;

@Mojo(name = "bloop", threadSafe = true, requiresProject = true, defaultPhase = LifecyclePhase.INITIALIZE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class BloopMojo extends ScalaCompileMojo {

    /**********************************************************************************************
     ******************************** BLOOP MOJO INIT OPTIONS *************************************
     *********************************************************************************************/

    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    @Component
    private MavenPluginManager mavenPluginManager;

    @Parameter(property = "bloop.configDirectory", defaultValue = "${session.executionRootDirectory}/.bloop-config")
    public File bloopConfigDir;

    @Parameter(property = "scala.artifactID", defaultValue = "scala-compiler")
    public String scalaArtifactID;

    public MavenProject getProject() {
        return super.project;
    }

    public String getScalaVersion() throws Exception {
        return super.findScalaVersion().toString();
    }

    public List<String> getScalacArgs() throws Exception {
        return super.getScalaOptions();
    }

    public List<String> getJavacArgs() throws Exception {
        return super.getJavacOptions();
    }

    public List<File> getCompileSourceDirectories() throws Exception {
        return super.getSourceDirectories();
    }

    public File getCompileOutputDir() throws Exception {
        return super.getOutputDir();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        BloopMojo initializedMojo = MojoImplementation.initializeMojo(project, session, mojoExecution, mavenPluginManager, encoding);
        MojoImplementation.writeCompileAndTestConfiguration(initializedMojo, this.getLog());
    }

    @Override
    protected void doExecute() throws Exception {
        return;
    }
}
