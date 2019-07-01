#!/usr/bin/env bash
# ------------------------------------------------------------------
# Disclaimer: this script is only meant to be run by our benchmarking
# infrastructure and running it in your machine can be fatal. Use with
# care and don't be afraid of forking it, removing most of the code
# and only running those benchmarks that you care the most about.
#
# More information on benchmarking bloop and sbt can be found in the
# performance reference published in the website.
# ------------------------------------------------------------------

set -o pipefail

BLOOP_DEFAULT_REFERENCE="master"
BLOOP_SMALL_JMH_OPTIONS="-wi 15 -i 10 -f1 -t1"
BLOOP_MEDIUM_JMH_OPTIONS="-wi 10 -i 10 -f1 -t1"
BLOOP_LARGE_JMH_OPTIONS="-wi 10 -i 5 -f1 -t1"
BLOOP_GIGANTIC_JMH_OPTIONS="-wi 7 -i 5 -f1 -t1"

BLOOP_REFERENCE="$BLOOP_DEFAULT_REFERENCE"
BLOOP_JMH_RUNNER="benchmarks/jmh:run"
BLOOP_HOME="$HOME/bloop-benchmarks"
BLOOP_LOGS_DIR="$HOME/bloop-logs"
BLOOP_REPO="https://github.com/scalacenter/bloop.git"

usage() {
    echo "Usage: ./run-benchmarks.sh -r | --ref <git-ref>       Build and benchmark the given reference."
    echo "                                                      Defaults to \"$BLOOP_DEFAULT_REFERENCE\""
    echo "                           --upload                   If set, upload the results to InfluxDB."
    echo "                           -js | --jmh-options-small  Pass the given options to JMH (small projects)."
    echo "                                                      Defaults to \"$BLOOP_SMALL_JMH_OPTIONS\""
    echo "                           -jm | --jmh-options-medium Pass the given options to JMH (medium projects)."
    echo "                                                      Defaults to \"$BLOOP_MEDIUM_JMH_OPTIONS\""
    echo "                           -jl | --jmh-options-large  Pass the given options to JMH (large projects)."
    echo "                                                      Defaults to \"$BLOOP_LARGE_JMH_OPTIONS\""
    echo "                           -h | --help                Show this message and exit."
    echo ""
    echo "Examples:"
    echo "  ./run-benchmarks.sh --ref +refs/pull/42/merge"
    echo "    Build and run the benchmarks on the pull request 42 (after merging)"
    echo "  ./run-benchmarks.sh --ref deadbeef --upload"
    echo "    Build and run the benchmarks on commit \"deadbeef\", uploads the results."
    echo "  ./run-benchmarks.sh --jmh-options-small \"-i 1 -wi 1 -f1 -t1\""
    echo "    Build and run the benchmarks on \"master\", runs small benchmarks with \"-i 1 -wi1 -f1 -t1\"."
}

main() {
    # This ensures we cannot run benchmarks concurrently (& there are no stale benchmark processes)
    (
      set -o pipefail
      (ps -C java -o pid && echo "A java process was found running.") || exit 1
    )

    # Delete the directory to start afresh (mkdir it)
    echo "Deleting $BLOOP_HOME"
    rm -rf "$BLOOP_HOME"
    echo "Creating $BLOOP_HOME"
    mkdir -p "$BLOOP_HOME"

    # Create logs dir if it doesn't exist
    mkdir -p "$BLOOP_LOGS_DIR"

    JMH_CMD="$BLOOP_JMH_RUNNER"
    SBT_COMMANDS=()

    pushd "$BLOOP_HOME"

    git clone "$BLOOP_REPO" .
    echo "git fetch origin $BLOOP_REFERENCE"
    git fetch origin "$BLOOP_REFERENCE"
    git checkout -qf FETCH_HEAD
    git submodule update --init --recursive

    echo "Setting up the machine before benchmarks..."
    /bin/bash "$BLOOP_HOME/benchmark-bridge/scripts/benv" set -nb -ns || exit 1

    SBT_COMMANDS+=("exportCommunityBuild")

    SCALAC_SBT_BLOOP_BENCHMARKS=(
      #"$BLOOP_LARGE_JMH_OPTIONS -p project=scala -p projectName=library"
      #"$BLOOP_SMALL_JMH_OPTIONS -p project=mini-better-files -p projectName=mini-better-files"
    )

    for benchmark in "${SCALAC_SBT_BLOOP_BENCHMARKS[@]}"; do
      SBT_COMMANDS+=("$JMH_CMD .*Hot.*Benchmark.* $benchmark")
    done

    SBT_BLOOP_BENCHMARKS=(
      "-wi 2 -i 2 -f1 -t1 -p project=spark -p projectName=spark-test"
      "-wi 4 -i 4 -f1 -t1 -p project=lichess -p projectName=lila-test"
      "-wi 15 -i 10 -f1 -t1 -p project=sbt -p projectName=sbtRoot"
      "-wi 8 -i 5 -f1 -t1 -p project=frontend -p projectName=root-test"
      "-wi 8 -i 5 -f1 -t1 -p project=finagle -p projectName=finagle-test"
      "-wi 10 -i 10 -f1 -t1 -p project=algebird -p projectName=algebird-test"
      "-wi 20 -i 10 -f1 -t1 -p project=scalatra -p projectName=scalatra-project-test"
      "-wi 20 -i 10 -f1 -t1 -p project=atlas -p projectName=root-test"
      "-wi 20 -i 10 -f1 -t1 -p project=grid -p projectName=grid-test"
      "-wi 7 -i 5 -f1 -t1 -p project=akka -p projectName=akka-test"
      "-wi 10 -i 5 -f1 -t1 -p project=circe -p projectName=circe-test"
      "-wi 10 -i 5 -f1 -t1 -p project=linkerd -p projectName=all-test"
      "-wi 20 -i 10 -f1 -t1 -p project=summingbird -p projectName=summingbird-test"
      "-wi 10 -i 5 -f1 -t1 -p project=http4s -p projectName=root-test"
      "-wi 20 -i 10 -f1 -t1 -p project=gatling -p projectName=gatling-parent-test"
      "-wi 5 -i 5 -f1 -t1 -p project=marathon -p projectName=marathon-test"
      "-wi 10 -i 5 -f1 -t1 -p project=coursier -p projectName=coursier-repo-test"
      "-wi 15 -i 10 -f1 -t1 -p project=prisma -p projectName=root-test"
      "-wi 5 -i 3 -f1 -t1 -p project=cats -p projectName=cats-test" # compiles hot in 3 minutes
      "-wi 2 -i 3 -f1 -t1 -p project=scalding -p projectName=scalding-test"
      "-wi 2 -i 3 -f1 -t1 -p project=scio -p projectName=scio+test"
    )

    JAVA_HOMES=(
      #"/usr/lib/jvm/java-10-oracle/bin/java"
      #"/usr/lib/jvm/java-8-shenandoah/bin/java"
      "/usr/lib/jvm/java-8-oracle/bin/java"
      #"/usr/lib/jvm/java-8-graal-ee/bin/java"
    )

    pidFile=$(mktemp /tmp/pid.XXXXXX)
    ASYNC_PROF_OPTS="-p pidFile=$pidFile -prof pl.project13.scala.jmh.extras.profiler.ForkedAsyncProfiler:asyncProfilerDir=/repos/async-profiler;flameGraphDir=/repos/FlameGraph;threads=true;framebuf=16777216;jfr=true;pidFile=$pidFile;"

    for java_home in "${JAVA_HOMES[@]}"; do
      for benchmark in "${SBT_BLOOP_BENCHMARKS[@]}"; do
        SBT_COMMANDS+=("$JMH_CMD .*Hot(Bloop|PipelinedBloop|Sbt)Benchmark.* $benchmark -jvm $java_home")
      done
    done

    #BLOOP_BENCHMARKS=("$BLOOP_SMALL_JMH_OPTIONS bloop.ProjectBenchmark")
    #for benchmark in "${BLOOP_BENCHMARKS[@]}"; do
    #    SBT_COMMANDS+=("$JMH_CMD $benchmark")
    #done

    TARGET_LOG_FILE="$BLOOP_LOGS_DIR/benchmarks-$(date --iso-8601=seconds).log"
    if ! sbt -no-colors "${SBT_COMMANDS[@]}" | tee "$TARGET_LOG_FILE"; then
      popd
      echo "BENCHMARKS FAILED. Log file is $TARGET_LOG_FILE"
      exit 1
    else
      popd
      echo "FINISHED OK. Log file is $TARGET_LOG_FILE"
    fi
}

while [ "$1" != "" ]; do
    case $1 in
        -r | --ref )                 shift
                                     BLOOP_REFERENCE=$1
                                     ;;
        --upload )                   BLOOP_JMH_RUNNER="benchmarks/jmh:runMain scala.bench.UploadingRunner"
                                     ;;

        -js | --jmh-options-small )  shift
                                     BLOOP_SMALL_JMH_OPTIONS=$1
                                     ;;
        -jm | --jmh-options-medium ) shift
                                     BLOOP_MEDIUM_JMH_OPTIONS=$1
                                     ;;
        -jl | --jmh-options-large )  shift
                                     BLOOP_LARGE_JMH_OPTIONS=$1
                                     ;;
        -h | --help )                usage
                                     exit 0
                                     ;;
        * )                          usage
                                     exit 1
    esac
    shift
done

main
