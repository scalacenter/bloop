package bloop.integrations.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import scala_maven.ScalaMojoSupport;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mojo(name = "bloop", threadSafe = true, requiresProject = true, defaultPhase = LifecyclePhase.INITIALIZE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class BloopMojo extends ScalaMojoSupport {

    /**********************************************************************************************
     ******************************** BLOOP MOJO INIT OPTIONS *************************************
     *********************************************************************************************/

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    public MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    public MavenSession session;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    public MojoExecution mojoExecution;

    @Component
    public MavenPluginManager mavenPluginManager;

    @Parameter(property = "bloop.configDirectory", defaultValue = "${session.executionRootDirectory}/.bloop-config")
    public File bloopConfigDir;

    @Parameter(property = "scala.artifactID", defaultValue = "scala-compiler")
    public String scalaArtifactID;

    /**********************************************************************************************
     ****************************** SCALA MAVEN PLUGIN OPTIONS ************************************
     *********************************************************************************************/

    @Parameter(property = "project.build.outputDirectory")
    public File outputDir;

    @Parameter(defaultValue = "${project.build.sourceDirectory}/../scala")
    public File sourceDir;

    @Parameter(defaultValue = "${project.build.directory}/analysis/compile")
    public File analysisCacheFile;

    @Parameter(property = "maven.test.skip")
    public boolean skip;

    @Parameter(defaultValue = "${project.build.testOutputDirectory}")
    public File testOutputDir;

    @Parameter(defaultValue = "${project.build.testSourceDirectory}/../scala")
    public File testSourceDir;

    @Parameter(property = "recompileMode", defaultValue = "all")
    public String recompileMode = "all";

    @Parameter(property = "notifyCompilation", defaultValue = "true")
    public boolean notifyCompilation = true;

    @Parameter(property = "compileOrder", defaultValue = "mixed")
    public String compileOrder;

    @Parameter(property = "useZincServer", defaultValue = "false")
    public boolean useZincServer;

    @Parameter(property = "zincPort", defaultValue = "3030")
    public int zincPort;

    @Parameter(property = "addZincArgs")
    public String addZincArgs = "";

    @Parameter(defaultValue = "true")
    public boolean sendJavaToScalac = true;

    @Parameter
    public Set<String> includes = new HashSet<String>();

    @Parameter
    public Set<String> excludes = new HashSet<String>();

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    public List<MavenProject> reactorProjects;

    @Parameter(property = "localRepository", required = true, readonly = true)
    public ArtifactRepository localRepo;

    @Parameter(property = "project.remoteArtifactRepositories", required = true, readonly = true)
    public List<ArtifactRepository> remoteRepos;

    @Parameter
    public BasicArtifact[] dependencies;

    @Parameter
    public BasicArtifact[] compilerPlugins;

    @Parameter
    public String[] jvmArgs;

    @Parameter
    public String[] args;

    @Parameter(property = "addScalacArgs")
    public String addScalacArgs;

    @Parameter(property = "maven.scala.className", defaultValue = "scala.tools.nsc.Main", required = true)
    public String scalaClassName;

    @Parameter(property = "scala.version")
    public String scalaVersion;

    @Parameter(property = "scala.organization", defaultValue = "org.scala-lang")
    public String scalaOrganization;

    @Parameter(property = "scala.compat.version")
    public String scalaCompatVersion;

    @Parameter(property = "scala.home")
    public String scalaHome;

    @Parameter(property = "javacArgs")
    public String[] javacArgs;

    @Parameter(property = "javacGenerateDebugSymbols", defaultValue = "true")
    public boolean javacGenerateDebugSymbols = true;

    @Parameter(property = "addJavacArgs")
    public String addJavacArgs;

    @Parameter(property = "maven.compiler.source")
    public String source;

    @Parameter(property = "maven.compiler.target")
    public String target;

    @Parameter(property = "project.build.sourceEncoding", defaultValue = "UTF-8")
    public String encoding;

    @Parameter(property = "displayCmd", defaultValue = "false", required = true)
    public boolean displayCmd;

    @Parameter(defaultValue = "true")
    public boolean fork = true;

    @Parameter(defaultValue = "false")
    public boolean forceUseArgFile = false;

    @Parameter(property = "maven.scala.checkConsistency", defaultValue = "true")
    public boolean checkMultipleScalaVersions;

    @Parameter(defaultValue = "false")
    public boolean failOnMultipleScalaVersions = false;

    @Parameter(property = "maven.scala.useCanonicalPath", defaultValue = "true")
    public boolean useCanonicalPath = true;

    @Parameter(property = "localRepository", required = true, readonly = true)
    public ArtifactRepository localRepository;

    @Parameter(defaultValue = "${plugin.artifacts}")
    public List<Artifact> pluginArtifacts;

    @Override
    protected void doExecute() throws Exception {
        BloopMojo initializedMojo = MojoImplementation.initializeMojo(project, session, mojoExecution, mavenPluginManager, encoding);
        MojoImplementation.writeCompileAndTestConfiguration(initializedMojo, this.getLog());
    }
}
