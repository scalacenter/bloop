package bloop.integrations.maven;

import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import scala_maven.ExtendedScalaContinuousCompileMojo;

import java.io.File;

@Mojo(name = "bloop", threadSafe = true, requiresProject = true, defaultPhase = LifecyclePhase.INITIALIZE, requiresDependencyResolution = ResolutionScope.TEST)
public class BloopMojo extends ExtendedScalaContinuousCompileMojo {
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    protected MojoExecution mojoExecution;

    @Component
    protected MavenPluginManager mavenPluginManager;

    @Parameter(property = "bloop.configDirectory", defaultValue = "${session.executionRootDirectory}/.bloop-config")
    protected File bloopConfigDir;

    @Parameter(property = "scala.artifactID", defaultValue = "scala-compiler")
    protected String scalaArtifactID;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        BloopMojo initializedMojo = MojoImplementation.initializeMojo(project, session, mojoExecution, mavenPluginManager, encoding);
        MojoImplementation.writeCompileAndTestConfiguration(initializedMojo, this.getLog());
    }

    public File getBloopConfigDir() {
        return bloopConfigDir;
    }

    public String getScalaArtifactID() {
        return scalaArtifactID;
    }
}
