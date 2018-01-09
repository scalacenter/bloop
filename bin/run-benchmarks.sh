#!/usr/bin/env bash
set -o pipefail

BLOOP_DEFAULT_REFERENCE="master"
BLOOP_SMALL_JMH_OPTIONS="-wi 10 -i 10 -f3 -t1"
BLOOP_MEDIUM_JMH_OPTIONS="-wi 10 -i 10 -f2 -t1"
BLOOP_LARGE_JMH_OPTIONS="-wi 10 -i 10 -f1 -t1"

BLOOP_REFERENCE="$BLOOP_DEFAULT_REFERENCE"
BLOOP_JMH_RUNNER="benchmarks/jmh:run"
BLOOP_HOME="$HOME/bloop-benchmarks"
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
    mkdir -p "$BLOOP_HOME"

    JMH_CMD="$BLOOP_JMH_RUNNER"
    LOG_FILE="$BLOOP_HOME/$(date +"%F-%H%M%S")-benchmarks-log.txt"
    TEMP_DIR=$(mktemp -d)
    COMMANDS_FILE="$TEMP_DIR/commands"

    pushd "$TEMP_DIR"

    git clone "$BLOOP_REPO" .
    git fetch origin "$BLOOP_REFERENCE"
    git checkout -qf FETCH_HEAD
    git submodule update --init --recursive

    echo "install" >> "$COMMANDS_FILE"
    echo "setupTests" >> "$COMMANDS_FILE"

    SCALAC_SBT_BLOOP_BENCHMARKS=("$BLOOP_LARGE_JMH_OPTIONS -p project=scala -p projectName=library"
                                 "$BLOOP_SMALL_JMH_OPTIONS -p project=mini-better-files -p projectName=mini-better-files")
    for benchmark in "${SCALAC_SBT_BLOOP_BENCHMARKS[@]}"
    do
        echo "$JMH_CMD .*Hot.*Benchmark.* $benchmark" >> "$COMMANDS_FILE"
    done

    SBT_BLOOP_BENCHMARKS=("$BLOOP_MEDIUM_JMH_OPTIONS -p project=sbt -p projectName=sbtRoot"
                          "$BLOOP_LARGE_JMH_OPTIONS -p project=scala -p projectName=compiler"
                          "$BLOOP_SMALL_JMH_OPTIONS -p project=utest -p projectName=root"
                          "$BLOOP_SMALL_JMH_OPTIONS -p project=versions -p projectName=versions"
                          "$BLOOP_SMALL_JMH_OPTIONS -p project=with-tests -p projectName=with-tests"
                          "$BLOOP_LARGE_JMH_OPTIONS -p project=frontend -p projectName=root"
                          "$BLOOP_LARGE_JMH_OPTIONS -p project=spark -p projectName=examples")
    for benchmark in "${SBT_BLOOP_BENCHMARKS[@]}"
    do
        echo "$JMH_CMD .*Hot(Sbt|Bloop)Benchmark.* $benchmark" >> "$COMMANDS_FILE"
    done

    BLOOP_BENCHMARKS=("$BLOOP_SMALL_JMH_OPTIONS bloop.ProjectBenchmark"
                      "$BLOOP_SMALL_JMH_OPTIONS bloop.engine.tasks.TaskBenchmark"
                      "$BLOOP_SMALL_JMH_OPTIONS bloop.logging.BloopLoggerBenchmark")
    for benchmark in "${BLOOP_BENCHMARKS[@]}"
    do
        echo "$JMH_CMD $benchmark" >> "$COMMANDS_FILE"
    done

    echo "exit" >> "$COMMANDS_FILE"

    sbt -no-colors < "$COMMANDS_FILE" | tee "$LOG_FILE"

    popd
    rm -rf "$TEMP_DIR"
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
