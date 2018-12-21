package bloop.launcher;

import coursier.CoursierPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  // The rules must be defined in a java file because they require public fields in the class file
  @Rule public final EnvironmentVariables environmentVariables = new EnvironmentVariables();
  @Rule public final ProvideSystemProperty myPropertyHasMyValue;

  public final Path binDirectory = Files.createTempDirectory("bsp-bin");
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

    // Install new directory at the beginning of the PATH so that we can make commands succeed/fail
    String oldPath = System.getenv("PATH");
    String tempBinDirectory = binDirectory.toAbsolutePath().toString();
    String newPath = tempBinDirectory + ":" + oldPath;
    environmentVariables.set("PATH", newPath);

    // Install a fake bloop so that the system bloop is not detected by the launcher
    Path fakeBloopPath = binDirectory.resolve("bloop");
    byte[] bytes = "I am not a script and I must fail to be executed".getBytes(StandardCharsets.UTF_8);
    Files.write(fakeBloopPath, bytes);
    fakeBloopPath.toFile().setExecutable(true);
    fakeBloopPath.toFile().deleteOnExit();

    Thread thread = new Thread(() -> {
      deleteRecursively(cwdPath);
      deleteRecursively(homePath);
      deleteRecursively(binDirectory);
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
