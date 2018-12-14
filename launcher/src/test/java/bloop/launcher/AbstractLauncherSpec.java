package bloop.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.contrib.java.lang.system.ProvideSystemProperty;

public abstract class AbstractLauncherSpec {
  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables();
  @Rule
  public final ProvideSystemProperty myPropertyHasMyValue;

  // Every test will get a new user.home and user.dir directories where it can play with
  public AbstractLauncherSpec() throws IOException {
    Path cwdPath = Files.createTempDirectory("cwd-test").toAbsolutePath();
    String cwd = cwdPath.toString();
    Path homePath = Files.createTempDirectory("launcher-home-test").toAbsolutePath();
    String homeDirectory = homePath.toString();
    this.myPropertyHasMyValue =
        new ProvideSystemProperty("user.dir", cwd).and("user.home", homeDirectory);

    // We mock the PATH environment variable not to find the user's bloop in the PATH
    String path = System.getenv("PATH");
    if (path != null) {
      Path firstPathDir = Paths.get(path.split(":")[0]);
      Path fakeBloopPath = firstPathDir.resolve("bloop");
      byte[] bytes = "i am not an executable and will be found by the OS and fail".getBytes();
      Files.write(fakeBloopPath, bytes);
      fakeBloopPath.toFile().setExecutable(true);
      fakeBloopPath.toFile().deleteOnExit();
    } else {
      // Only log, let's not throw in the initializer of the spec
      System.err.println("Error: environment variable PATH is empty");
    }

    cwdPath.toFile().deleteOnExit();
    homePath.toFile().deleteOnExit();
  }
}
