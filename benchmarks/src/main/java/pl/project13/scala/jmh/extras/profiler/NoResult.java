package pl.project13.scala.jmh.extras.profiler;

import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.Aggregator;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.util.Statistics;

import java.util.Collection;
import java.util.LinkedHashSet;

class NoResult extends Result<NoResult> {
	private static final String LINE_SEPARATOR = System.getProperty("line.separator");
	private String name;
	private final LinkedHashSet<String> output;

	public NoResult(String name, String output) {
		this(name, singletonSet(output));

	}

	public NoResult(String name, LinkedHashSet<String> output) {
		super(ResultRole.SECONDARY, name, of(Double.NaN), "N/A", AggregationPolicy.SUM);
		this.name = name;
		this.output = output;
	}

	private static LinkedHashSet<String> singletonSet(String s) {
		LinkedHashSet<String> strings = new LinkedHashSet<>();
		strings.add(s);
		return strings;
	}

	@Override
	protected Aggregator<NoResult> getThreadAggregator() {
		return new NoResultAggregator();
	}

	@Override
	protected Aggregator<NoResult> getIterationAggregator() {
		return new NoResultAggregator();
	}

	@Override
	public String extendedInfo() {
		StringBuilder out = new StringBuilder();
		for (String s : output) {
			out.append(s).append(LINE_SEPARATOR);
		}
		return out.toString();
	}

	private class NoResultAggregator implements Aggregator<NoResult> {
		@Override
		public NoResult aggregate(Collection<NoResult> results) {
			LinkedHashSet<String> strings = new LinkedHashSet<>();
			for (NoResult r : results) {
				strings.addAll(r.output);
			}
			return new NoResult(name, strings);
		}
	}
}
