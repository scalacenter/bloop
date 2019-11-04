package bloop.bloop4j;

import org.eclipse.lsp4j.jsonrpc.validation.NonNull;
import org.eclipse.xtext.xbase.lib.Pure;
import org.eclipse.xtext.xbase.lib.util.ToStringBuilder;

@SuppressWarnings("all")
public class BloopStopClientCachingParams {
  private String originId;
  
  public BloopStopClientCachingParams(@NonNull final String originId) {
    this.originId = originId;
  }
  
  @Pure
  public String getOriginId() {
    return this.originId;
  }
  
  public void setOriginId(final String originId) {
    this.originId = originId;
  }
  
  @Override
  @Pure
  public String toString() {
    ToStringBuilder b = new ToStringBuilder(this);
    b.add("originId", this.originId);
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
    BloopStopClientCachingParams other = (BloopStopClientCachingParams) obj;
    if (this.originId == null) {
      if (other.originId != null)
        return false;
    } else if (!this.originId.equals(other.originId))
      return false;
    return true;
  }
  
  @Override
  @Pure
  public int hashCode() {
    return 31 * 1 + ((this.originId== null) ? 0 : this.originId.hashCode());
  }
}
