package pl.project13.scala.jmh.extras.profiler;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.openjdk.jmh.profile.ProfilerException;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class ProfilerUtils {
	static final String FLAME_GRAPH_DIR = "FLAME_GRAPH_DIR";

	public static OptionSet parseInitLine(String initLine, OptionParser parser) throws ProfilerException {
		try {
			Method method = Class.forName("org.openjdk.jmh.profile.ProfilerUtils").getDeclaredMethod("parseInitLine",
					String.class, OptionParser.class);
			method.setAccessible(true);
			return (OptionSet) method.invoke(null, initLine, parser);
		} catch (InvocationTargetException ex) {
			throw (ProfilerException) ex.getCause();
		} catch (NoSuchMethodException | IllegalArgumentException | IllegalAccessException | ClassNotFoundException
				| SecurityException e) {
			throw new ProfilerException(e);
		}
	}

	static void startAndWait(ProcessBuilder processBuilder, boolean verbose) {
		String commandLine = processBuilder.command().stream().collect(Collectors.joining(" "));
		try {
			processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
			if (verbose)
				System.out.println(commandLine);
			Process process = processBuilder.start();
			if (process.waitFor() != 0) {
				throw new RuntimeException("Non zero exit code from: " + commandLine);
			}
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("Error running " + commandLine, e);
		}
	}

	static void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	static Path flameGraph(Path collapsedPath, List<String> extra, String suffix, Path flameGraphHome,
			Collection<? extends String> flameGraphOptions, Path outputDir, String eventName, boolean verbose) {
		ArrayList<String> args = new ArrayList<>();
		args.add("perl");
		args.add(flameGraphHome.resolve("flamegraph.pl").toAbsolutePath().toString());
		args.addAll(flameGraphOptions);
		args.addAll(extra);
		args.add(collapsedPath.toAbsolutePath().toString());
		Path outputFile = outputDir.resolve("flame-graph-" + eventName + suffix + ".svg");
		startAndWait(new ProcessBuilder(args).redirectOutput(outputFile.toFile()), verbose);
		return outputFile;
	}

	static OptionSpec<String> addFlameGraphDirOption(OptionParser parser) {
		return parser
				.accepts("flameGraphDir",
						"Location of clone of https://github.com/brendangregg/FlameGraph. Also can be provided as $"
								+ ProfilerUtils.FLAME_GRAPH_DIR)
				.withRequiredArg().ofType(String.class).describedAs("directory");
	}

	static Path findFlamegraphDir(OptionSpec<String> flameGraphDir, OptionSet options) {
		String flameGraphHome = System.getenv(FLAME_GRAPH_DIR);
		if (options.has(flameGraphDir)) {
			return Paths.get(options.valueOf(flameGraphDir));
		} else {
			if (flameGraphHome != null) {
				return Paths.get(flameGraphHome);
			} else {
				return null;
			}
		}
	}
}
