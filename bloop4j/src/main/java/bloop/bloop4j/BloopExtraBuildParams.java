package bloop.bloop4j;

import java.util.List;
import org.eclipse.xtext.xbase.lib.Pure;
import org.eclipse.xtext.xbase.lib.util.ToStringBuilder;

@SuppressWarnings("all")
public class BloopExtraBuildParams {
  private Boolean ownsBuildFiles;
  
  private String clientClassesRootDir;
  
  private String semanticdbVersion;
  
  private List<String> supportedScalaVersions;
  
  @Pure
  public Boolean getOwnsBuildFiles() {
    return this.ownsBuildFiles;
  }
  
  public void setOwnsBuildFiles(final Boolean ownsBuildFiles) {
    this.ownsBuildFiles = ownsBuildFiles;
  }
  
  @Pure
  public String getClientClassesRootDir() {
    return this.clientClassesRootDir;
  }
  
  public void setClientClassesRootDir(final String clientClassesRootDir) {
    this.clientClassesRootDir = clientClassesRootDir;
  }
  
  @Pure
  public String getSemanticdbVersion() {
    return this.semanticdbVersion;
  }
  
  public void setSemanticdbVersion(final String semanticdbVersion) {
    this.semanticdbVersion = semanticdbVersion;
  }
  
  @Pure
  public List<String> getSupportedScalaVersions() {
    return this.supportedScalaVersions;
  }
  
  public void setSupportedScalaVersions(final List<String> supportedScalaVersions) {
    this.supportedScalaVersions = supportedScalaVersions;
  }
  
  @Override
  @Pure
  public String toString() {
    ToStringBuilder b = new ToStringBuilder(this);
    b.add("ownsBuildFiles", this.ownsBuildFiles);
    b.add("clientClassesRootDir", this.clientClassesRootDir);
    b.add("semanticdbVersion", this.semanticdbVersion);
    b.add("supportedScalaVersions", this.supportedScalaVersions);
    return b.toString();
  }
  
  @Override
  @Pure
  public boolean equals(final Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    BloopExtraBuildParams other = (BloopExtraBuildParams) obj;
    if (this.ownsBuildFiles == null) {
      if (other.ownsBuildFiles != null)
        return false;
    } else if (!this.ownsBuildFiles.equals(other.ownsBuildFiles))
      return false;
    if (this.clientClassesRootDir == null) {
      if (other.clientClassesRootDir != null)
        return false;
    } else if (!this.clientClassesRootDir.equals(other.clientClassesRootDir))
      return false;
    if (this.semanticdbVersion == null) {
      if (other.semanticdbVersion != null)
        return false;
    } else if (!this.semanticdbVersion.equals(other.semanticdbVersion))
      return false;
    if (this.supportedScalaVersions == null) {
      if (other.supportedScalaVersions != null)
        return false;
    } else if (!this.supportedScalaVersions.equals(other.supportedScalaVersions))
      return false;
    return true;
  }
  
  @Override
  @Pure
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((this.ownsBuildFiles== null) ? 0 : this.ownsBuildFiles.hashCode());
    result = prime * result + ((this.clientClassesRootDir== null) ? 0 : this.clientClassesRootDir.hashCode());
    result = prime * result + ((this.semanticdbVersion== null) ? 0 : this.semanticdbVersion.hashCode());
    return prime * result + ((this.supportedScalaVersions== null) ? 0 : this.supportedScalaVersions.hashCode());
  }
}
