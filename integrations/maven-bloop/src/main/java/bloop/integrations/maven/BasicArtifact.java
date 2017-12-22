package bloop.integrations.maven;

/**
 * Defines a basic artifact for the configuration of the plugin.
 *
 * It is copy-pasted from scala maven plugin so that there's a one to one parity between them.
 */
public class BasicArtifact {
    public String groupId;
    public String artifactId;
    public String version;
    public String classifier;

    @Override
    public String toString() {
        return "BasicArtifact(" + groupId + "," + artifactId + "," + version + "," + classifier + ")";
    }
}
