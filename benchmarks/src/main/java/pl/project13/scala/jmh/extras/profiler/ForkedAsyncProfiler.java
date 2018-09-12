package pl.project13.scala.jmh.extras.profiler;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.runner.IterationType;

public class ForkedAsyncProfiler implements InternalProfiler {
  public static String HELLO = "";
  private static final String ASYNC_PROFILER_DIR = "ASYNC_PROFILER_DIR";

  private static final String DEFAULT_EVENT = "cpu";
  private static final long DEFAULT_FRAMEBUF = 8 * 1024 * 1024;
  private static final long DEFAULT_INTERVAL = 1000000;

  private final String event;
  private final Directions directions;
  private final Path asyncProfilerDir;
  private final boolean threads;
  private final Boolean simpleName;
  private Path outputDir;
  private boolean started;
  private final Path pidFile;

  private Path profiler;
  private Path jattach;
  private Long framebuf;
  private Long interval;
  private long pid = -1;
  private int measurementIterationCount;
  private Path flameGraphDir;
  private Collection<? extends String> flameGraphOpts = Collections.emptyList();
  private boolean verbose = false;
  private List<Path> generated = new ArrayList<>();

  public ForkedAsyncProfiler(String initLine) throws ProfilerException {
    OptionParser parser = new OptionParser();
    OptionSpec<String> outputDir = parser.accepts("dir", "Output directory").withRequiredArg().describedAs("directory").ofType(String.class);
    OptionSpec<String> asyncProfilerDir = parser.accepts("asyncProfilerDir", "Location of clone of https://github.com/jvm-profiling-tools/async-profiler. Also can be provided as $" + ASYNC_PROFILER_DIR).withRequiredArg().ofType(String.class).describedAs("directory");
    OptionSpec<String> pidFile = parser.accepts("pidFile", "File location where the PID of the process will be read from").withRequiredArg().ofType(String.class).describedAs("pidFile");
    OptionSpec<String> event = parser.accepts("event", "Event to sample: cpu, alloc, lock, cache-misses etc.").withRequiredArg().ofType(String.class).defaultsTo("cpu");
    OptionSpec<Long> framebuf = parser.accepts("framebuf", "Size of profiler framebuffer").withRequiredArg().ofType(Long.class).defaultsTo(DEFAULT_FRAMEBUF);
    OptionSpec<Long> interval = parser.accepts("interval", "Profiling interval, in nanoseconds").withRequiredArg().ofType(Long.class).defaultsTo(DEFAULT_INTERVAL);
    OptionSpec<Boolean> threads = parser.accepts("threads", "Profile threads separately").withRequiredArg().ofType(Boolean.class).defaultsTo(false,true);
    OptionSpec<Boolean> verbose = parser.accepts("verbose", "Output the sequence of commands").withRequiredArg().ofType(Boolean.class).defaultsTo(false);
    OptionSpec<String> flameGraphOpts = parser.accepts("flameGraphOpts", "Options passed to FlameGraph.pl").withRequiredArg().withValuesSeparatedBy(',').ofType(String.class);
    OptionSpec<Directions> flameGraphDirection = parser.accepts("flameGraphDirection", "Directions to generate flamegraphs").withRequiredArg().ofType(Directions.class).defaultsTo(Directions.values());
    OptionSpec<String> flameGraphDir = ProfilerUtils.addFlameGraphDirOption(parser);
    OptionSpec<Boolean> simpleName = parser.accepts("simpleName", "Use simple names in flamegraphs").withRequiredArg().ofType(Boolean.class);


    OptionSet options = ProfilerUtils.parseInitLine(initLine, parser);
    if (options.has(event)) {
      this.event = options.valueOf(event);
    } else {
      this.event = DEFAULT_EVENT;
    }
    if (options.has(framebuf)) {
      this.framebuf = options.valueOf(framebuf);
    } else {
      this.framebuf = DEFAULT_FRAMEBUF;
    }
    if (options.has(interval)) {
      this.interval = options.valueOf(interval);
    } else {
      this.interval = DEFAULT_INTERVAL;
    }
    if (options.has(outputDir)) {
      this.outputDir = Paths.get(options.valueOf(outputDir));
      createOutputDirectories();
    }

    if (options.has(flameGraphOpts)) {
      this.flameGraphOpts = options.valuesOf(flameGraphOpts);
    }
    if (options.has(flameGraphDirection)) {
      this.directions = options.valueOf(flameGraphDirection);
    } else {
      this.directions = Directions.BOTH;
    }
    if (options.has(threads)) {
      this.threads = options.valueOf(threads);
    } else {
      this.threads = false;
    }
    if (options.has(verbose)) {
      this.verbose = options.valueOf(verbose);
    }
    if (options.has(simpleName)) {
      this.simpleName = options.valueOf(simpleName);
    } else {
      this.simpleName = false;
    }
    this.flameGraphDir = ProfilerUtils.findFlamegraphDir(flameGraphDir, options);
    this.asyncProfilerDir = lookupAsyncProfilerHome(asyncProfilerDir, options);
    this.pidFile = lookupPidFile(pidFile, options);
    Path build = this.asyncProfilerDir.resolve("build");
    Path profiler1 = build.resolve("libasyncProfiler.so");
    if (!Files.exists(profiler1)) {
      throw new ProfilerException(profiler1 + " does not exist");
    } else {
      this.profiler = profiler1;
      Path jattach1 = build.resolve("jattach");
      if (!Files.exists(jattach1)) {
        throw new ProfilerException(jattach1 + " does not exist");
      } else {
        this.jattach = jattach1;
      }
    }

  }

  private Path lookupPidFile(OptionSpec<String> pidFile, OptionSet options) throws ProfilerException {
    if (options.has(pidFile)) {
      return Paths.get(options.valueOf(pidFile));
    } else {
      throw new ProfilerException("Location of pid file must be provided to enable async profiler");
    }
  }

  private Path lookupAsyncProfilerHome(OptionSpec<String> asyncProfilerDir, OptionSet options) throws ProfilerException {
    if (options.has(asyncProfilerDir)) {
      return Paths.get(options.valueOf(asyncProfilerDir));
    } else {
      String env = System.getenv(ASYNC_PROFILER_DIR);
      if (env == null) {
        throw new ProfilerException("Location of async-profiler-dir must be set with environment variable ASYNC_PROFILER_DIR or corresponding profiler option");
      }
      return Paths.get(env);
    }
  }

  private void createOutputDirectories() {
    try {
      Files.createDirectories(this.outputDir);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
    if (!started && iterationParams.getType() == IterationType.MEASUREMENT) {
      try {
        if (this.pid == -1) {
          this.pid = Long.getLong(new String(Files.readAllBytes(this.pidFile)));
        }
        String threadOpt = this.threads ? ",threads" : "";
        profilerCommand(String.format("start,event=%s%s,framebuf=%d,interval=%d", event, threadOpt, framebuf, interval));
        started = true;
      } catch (IOException e) {
        throw new RuntimeException("PID file " + this.pidFile.toAbsolutePath().toString() + " could not be read!");
      }
    }
  }

  @Override
  public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams, IterationResult result) {
    if (iterationParams.getType() == IterationType.MEASUREMENT) {
      measurementIterationCount += 1;
      if (measurementIterationCount == iterationParams.getCount()) {
        if (outputDir == null) {
          outputDir = createTempDir(benchmarkParams.id().replaceAll("/", "-"));
        }
        Path collapsedPath = outputDir.resolve("collapsed-" + event.toLowerCase() + ".txt");
        profilerCommand(String.format("stop,file=%s,collapsed", collapsedPath));
        generated.add(collapsedPath);
        Path collapsedProcessedPath = collapsedPath;
        if (simpleName) {
          collapsedProcessedPath = outputDir.resolve("collapsed-simple-" + event.toLowerCase() + ".txt");
          generated.add(collapsedProcessedPath);
          replaceAllInFileLines(collapsedPath, collapsedProcessedPath, Pattern.compile("(^|;)[^;]*\\/"));
        }

        Path summaryPath = outputDir.resolve("summary.txt");
        profilerCommand(String.format("stop,file=%s,summary", summaryPath));
        generated.add(summaryPath);
        if (flameGraphDir != null) {
          if (EnumSet.of(Directions.FORWARD, Directions.BOTH).contains(directions)) {
            flameGraph(collapsedProcessedPath, Collections.emptyList(), "");
          }
          if (EnumSet.of(Directions.REVERSE, Directions.BOTH).contains(directions)) {
            flameGraph(collapsedProcessedPath, Arrays.asList("--reverse"), "-reverse");
          }
        }
      }
    }

    return Collections.singletonList(result());
  }

  private void replaceAllInFileLines(Path in, Path out, Pattern pattern) {
    try (Stream<String> lines = Files.lines(in)){
      Stream<CharSequence> mapped = lines.map(line -> pattern.matcher(line).replaceAll("$1"));
      Files.write(out, mapped::iterator);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void flameGraph(Path collapsedPath, List<String> extra, String suffix) {
    generated.add(ProfilerUtils.flameGraph(collapsedPath, extra, suffix, flameGraphDir, flameGraphOpts, outputDir, event, verbose));
  }

  private NoResult result() {
    StringBuilder result = new StringBuilder();
    for (Path path : generated) {
      result.append("\n").append(path.toAbsolutePath().toString());
    }
    return new NoResult("async-profiler", result.toString());
  }

  public static synchronized long getPidOfProcess(Process p) {
    long pid = -1;

    try {
      if (p.getClass().getName().equals("java.lang.UNIXProcess")) {
        Field f = p.getClass().getDeclaredField("pid");
        f.setAccessible(true);
        pid = f.getLong(p);
        f.setAccessible(false);
      }
    } catch (Exception e) {
      pid = -1;
    }
    return pid;
  }

  private void profilerCommand(String command) {
    long pid = this.pid;

    ProcessBuilder processBuilder = new ProcessBuilder(jattach.toAbsolutePath().toString(), String.valueOf(pid), "load", profiler.toAbsolutePath().toString(), "true", command);
    if (verbose) {
      processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    }
    ProfilerUtils.startAndWait(processBuilder, verbose);
  }

  private Path createTempDir(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getDescription() {
    return "Profiling using async-profiler";
  }
}
