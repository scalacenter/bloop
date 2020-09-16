package bloop.integrations.maven;

import bloop.config.Config;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import scala.util.Either;
import scala_maven.AppLauncher;
import scala_maven.ExtendedScalaContinuousCompileMojo;

import java.io.File;
import java.util.List;

@Mojo(name = "bloopInstall", threadSafe = true, requiresProject = true, defaultPhase = LifecyclePhase.GENERATE_RESOURCES, requiresDependencyResolution = ResolutionScope.TEST)
public class BloopMojo extends ExtendedScalaContinuousCompileMojo {
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    @Component
    private MavenPluginManager mavenPluginManager;

    @Parameter(property = "bloop.configDirectory", defaultValue = "${session.executionRootDirectory}/.bloop")
    private File bloopConfigDir;

    @Parameter(property = "scala.artifactID", defaultValue = "scala-compiler")
    private String scalaArtifactID;

    @Parameter(property = "launcher")
    private String launcher;

    @Parameter(property = "addRunArgs", name = "addRunArgs")
    private String addRunArgs;

    @Parameter(property = "bloop.executionFork", defaultValue = "false")
    private boolean bloopExecutionFork;

    @Parameter(property = "downloadSources", defaultValue = "false")
    private boolean downloadSources;

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

    @Parameter(property = "bloop.secondaryCacheDir", defaultValue = "${session.executionRootDirectory}/.bloop/cache")
    private File secondaryCacheDir;

    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "compileOrder", defaultValue = "Mixed" )
    private CompileOrder compileOrder;

    private ModuleType moduleType;
    private List<String> javaCompilerArgs;

    private AppLauncher[] launchers;

    @Parameter(property = "sourceDir", defaultValue = "$mainSourceDir")
    private String sourceDir;

    protected String mainClass;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Either<String, BloopMojo> initializedMojo = MojoImplementation.initializeMojo(
                project, session, mojoExecution, mavenPluginManager, encoding);
        if (initializedMojo.isLeft()) {
            getLog().warn("Skipping configuration file generation: " + initializedMojo.left().get());
            return;
        }
        final BloopMojo bloopMojo = initializedMojo.right().get();
        MojoImplementation.writeCompileAndTestConfiguration(bloopMojo, session, this.getLog());
    }

    public File[] getAllScalaJars() throws Exception {
        if (moduleType == ModuleType.SCALA)
        {
            File libraryJar = getLibraryJar();
            File compilerJar = getCompilerJar();
            File[] mainJars = new File[]{libraryJar, compilerJar};
            List<File> extraJars = getCompilerDependencies();
            extraJars.remove( libraryJar );
            return (File[]) ArrayUtils.addAll( mainJars, extraJars.toArray() );
        } else return new File[0];
    }

    public File getBloopConfigDir() {
        return bloopConfigDir;
    }

    public boolean shouldDownloadSources(){
        return downloadSources;
    }

    private Config.CompileOrder getCompileOrder() {
        if ( this.moduleType == ModuleType.JAVA ) {
            return Config.JavaThenScala$.MODULE$;
        }
        switch ( this.compileOrder ) {
        case JavaThenScala:
            return Config.JavaThenScala$.MODULE$;
        case ScalaThenJava:
            return Config.ScalaThenJava$.MODULE$;
        default:
            return Config.Mixed$.MODULE$;
        }
    }

    public Config.CompileSetup getCompileSetup() {

        return new Config.CompileSetup(getCompileOrder(),
                                       classpathOptionsBootLibrary,
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

    public List<String> getJavacArgs() throws Exception {
        List<String> args = super.getJavacOptions();
        if (this.javaCompilerArgs != null) {
            args.addAll( javaCompilerArgs );
        }
        return args;
    }

    public void setModuleType( ModuleType moduleType )
    {
        this.moduleType = moduleType;
    }

    public void setJavaCompilerArgs( List<String> javaCompilerArgs )
    {
        this.javaCompilerArgs = javaCompilerArgs;
    }

    public static enum ModuleType {
        SCALA, JAVA;
    }

    public static enum CompileOrder {
        Mixed, JavaThenScala, ScalaThenJava;
    }
}
