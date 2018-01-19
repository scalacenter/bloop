package bloop.integrations.maven;

import bloop.integrations.ClasspathOptions;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import scala_maven.AppLauncher;
import scala_maven.ExtendedScalaContinuousCompileMojo;

import java.io.File;
import java.util.List;

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

    @Parameter(property = "bloop.executionFork", defaultValue = "false")
    private boolean bloopExecutionFork;

    @Parameter(property = "bloop.classpathOptions.bootLibrary", defaultValue = "true")
    private boolean classpathOptionsBootLibrary;

    @Parameter(property = "bloop.classpathOptions.compiler", defaultValue = "false")
    private boolean classpathOptionsCompiler;

    @Parameter(property = "bloop.classpathOptions.extra", defaultValue = "false")
    private boolean classpathOptionsExtra;

    @Parameter(property = "bloop.classpathOptions.autoBoot", defaultValue = "true")
    private boolean classpathOptionsAutoBoot;

    @Parameter(property = "bloop.classpathOptions.filterLibrary", defaultValue = "true")
    private boolean classpathOptionsFilterLibrary;

    @Parameter
    private AppLauncher[] launchers;

    protected String mainClass;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        BloopMojo initializedMojo = MojoImplementation.initializeMojo(project, session, mojoExecution, mavenPluginManager, encoding);
        MojoImplementation.writeCompileAndTestConfiguration(initializedMojo, this.getLog());
    }

    public File[] getAllScalaJars() throws Exception {
        File libraryJar = getLibraryJar();
        File compilerJar = getCompilerJar();
        File[] mainJars = new File[] {libraryJar, compilerJar};
        List<File> extraJars = getCompilerDependencies();
        extraJars.remove(libraryJar);
        return (File[]) ArrayUtils.addAll(mainJars, extraJars.toArray());
    }

    public File getBloopConfigDir() {
        return bloopConfigDir;
    }

    public ClasspathOptions getClasspathOptions() {
        return new ClasspathOptions(classpathOptionsBootLibrary,
                                    classpathOptionsCompiler,
                                    classpathOptionsExtra,
                                    classpathOptionsAutoBoot,
                                    classpathOptionsFilterLibrary);
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
