package bloop.cli.util;

import java.util.ArrayList;

import coursier.cache.shaded.dirs.GetWinDirs;
import coursier.jniutils.WindowsKnownFolders;

// has to be written in Java, in order to override a Java var-args method...
public class JniGetWinDirs implements GetWinDirs {
  @Override
  public String[] getWinDirs(String... guids) {
    ArrayList<String> list = new ArrayList<>();
    for (int i = 0; i < guids.length; i++) {
      list.add(WindowsKnownFolders.knownFolderPath("{" + guids[i] + "}"));
    }
    return list.toArray(new String[list.size()]);
  }
}
