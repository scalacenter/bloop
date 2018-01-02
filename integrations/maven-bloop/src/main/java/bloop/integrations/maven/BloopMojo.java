package bloop.integrations.maven;

import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import scala_maven.AppLauncher;
import scala_maven.ExtendedScalaContinuousCompileMojo;

import java.io.File;

@Mojo(name = "bloop", threadSafe = true, requiresProject = true, defaultPhase = LifecyclePhase.INITIALIZE, requiresDependencyResolution = ResolutionScope.TEST)
public class BloopMojo extends ExtendedScalaContinuousCompileMojo {
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    @Component
    private MavenPluginManager mavenPluginManager;

    @Parameter(property = "bloop.configDirectory", defaultValue = "${session.executionRootDirectory}/.bloop-config")
    private File bloopConfigDir;

    @Parameter(property = "scala.artifactID", defaultValue = "scala-compiler")
    private String scalaArtifactID;

    @Parameter(property = "launcher")
    private String launcher;

    @Parameter(property = "addRunArgs", name = "addRunArgs")
    private String addRunArgs;

    @Parameter
    private AppLauncher[] launchers;

    /**
     * Main class to call, the call use the jvmArgs and args define in the pom.xml, and the addRunArgs define in the command line if define.
     * <p>
     * Higher priority to launcher parameter)
     * Using this parameter only from command line (-DmainClass=...), not from pom.xml.
     *
     * @parameter property="mainClass"
     */
    protected String mainClass;

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

    public String getLauncher() {
        if (launcher == null) return "";
        else return launcher;
    }

    public String getAddRunArgs() {
        if (addRunArgs == null) return "";
        else return addRunArgs;
    }

    public AppLauncher[] getLaunchers() {
        if (launchers == null) return new AppLauncher[0];
        else return launchers;
    }
}
