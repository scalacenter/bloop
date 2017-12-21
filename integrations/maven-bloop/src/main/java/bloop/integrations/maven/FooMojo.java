package bloop.integrations.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Says "Hi" to the user.
 */
@Mojo(name = "bloop", threadSafe = true, requiresProject = true, requiresDependencyResolution = ResolutionScope.COMPILE)
public class FooMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "bloop.scalaOrganization", defaultValue = "org.scala-lang")
    private String scalaOrganization;

    @Parameter(property = "bloop.scalaName", defaultValue = "scala-compiler")
    private String scalaName;

    public void execute() throws MojoExecutionException {
        String name = project.getArtifactId();
        getLog().info("Hello, " + name);
    }
}
