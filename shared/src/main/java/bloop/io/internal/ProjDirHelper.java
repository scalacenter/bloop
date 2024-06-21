package bloop.io.internal;

import coursierapi.shaded.coursier.cache.shaded.dirs.GetWinDirs;
import coursierapi.shaded.coursier.cache.shaded.dirs.ProjectDirectories;
import coursierapi.shaded.coursier.jniutils.WindowsKnownFolders;
import coursierapi.shaded.coursier.paths.Util;

import java.util.ArrayList;

public class ProjDirHelper {
  public static String cacheDir() {
    return ((ProjectDirectories) get()).cacheDir;
  }
  public static String dataDir() {
    return ((ProjectDirectories) get()).dataDir;
  }
  // not letting ProjectDirectories leak in the signature, getting weird scalac crashes
  // with Scala 2.12.5 (because of shading?)
  private static Object get() {
    GetWinDirs getWinDirs;
    if (Util.useJni()) {
      getWinDirs = new GetWinDirs() {
        public String[] getWinDirs(String ...guids) {
          ArrayList<String> l = new ArrayList<>();
          for (int idx = 0; idx < guids.length; idx++) {
            l.add(WindowsKnownFolders.knownFolderPath("{" + guids[idx] + "}"));
          }
          return l.toArray(new String[l.size()]);
        }
      };
    } else {
      getWinDirs = GetWinDirs.powerShellBased;
    }
    return ProjectDirectories.from("", "", "bloop", getWinDirs);
  }
}
