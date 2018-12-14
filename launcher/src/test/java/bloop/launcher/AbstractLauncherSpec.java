package bloop.launcher;

import coursier.CoursierPaths;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.contrib.java.lang.system.ProvideSystemProperty;

public abstract class AbstractLauncherSpec {
  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables();
  @Rule
  public final ProvideSystemProperty myPropertyHasMyValue;
  public final String realHomeDirectory = System.getProperty("user.home");

  // Every test will get a new user.home and user.dir directories where it can play with
  public AbstractLauncherSpec() throws IOException {
    Path cwdPath = Files.createTempDirectory("cwd-test").toAbsolutePath();
    String cwd = cwdPath.toString();
    Path homePath = Files.createTempDirectory("launcher-home-test").toAbsolutePath();
    String homeDirectory = homePath.toString();

    // We set the real ivy home and coursier cache to speed up the tests
    String ivyHome = Paths.get(realHomeDirectory).resolve(".ivy2").toAbsolutePath().toString();
    String coursierCache = CoursierPaths.cacheDirectory().toPath().toAbsolutePath().toString();

    this.myPropertyHasMyValue =
        new ProvideSystemProperty("user.dir", cwd)
            .and("user.home", homeDirectory)
            .and("ivy.home", ivyHome)
            .and("coursier.cache", coursierCache);

    // We mock the PATH environment variable not to find the user's bloop in the PATH
    String path = System.getenv("PATH");
    if (path != null) {
      // We have to write a bloop binary in the first PATH entry because we cannot redefine PATH
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

    Thread thread = new Thread(() -> {
      deleteRecursively(cwdPath);
      deleteRecursively(homePath);
    });

    // Do this so that we remove all the crap we generate per test case
    Runtime.getRuntime().addShutdownHook(thread);
  }

  protected void deleteRecursively(Path directory) {
    try {
      Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch(IOException e) {
      return;
    }
  }
}
