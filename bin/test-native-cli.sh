#!/usr/bin/env bash
#
# Smoke-tests a freshly built native Bloop CLI binary (passed as the first argument).
#
# The binary is built with -H:-ParseRuntimeOptions (see build.sbt), so the native
# launcher must not consume -D/-X/-XX: arguments at startup:
#
#   1. A -D before the command is a launcher property that Bloop.main extracts before
#      command selection; the command must still run. If extraction regressed, the -D
#      would be treated as the command and `about` would not print its banner.
#   2. A -D after `--` must survive the launcher, cross the server, and reach the test
#      framework. This is checked end to end: Scala CLI generates a real project (with
#      its .bloop configuration in .scala-build) and the binary runs a ScalaTest suite
#      that prints the ConfigMap it received.
#
# Requires `scala-cli` on the PATH and a Bloop server version resolvable by the binary
# (in CI, `sbt publishLocal` runs before this script).

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: $0 <path-to-bloop-cli-binary>" >&2
  exit 1
fi

bin=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")

"$bin" about

out=$("$bin" -Dbloop.smoke.marker=1 about 2>&1 || true)
echo "$out"
echo "$out" | grep -F 'Maintained by'

workspace=$(mktemp -d)
trap 'rm -rf "$workspace"' EXIT

cat > "$workspace/Smoke.test.scala" <<'EOF'
//> using dep org.scalatest::scalatest::3.2.19
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap}

class SmokeTest extends AnyFunSuite with BeforeAndAfterAllConfigMap {
  override def beforeAll(cm: ConfigMap): Unit = println(s"configMap=$cm")
  test("ok") { assert(true) }
}
EOF

(cd "$workspace" && scala-cli compile --test .)

testProject=$(basename "$workspace"/.scala-build/.bloop/*-test.json .json)
out=$(cd "$workspace/.scala-build" && "$bin" test "$testProject" -- -Dkey=value 2>&1 || true)
echo "$out"
echo "$out" | grep -F 'configMap=Map(key -> value)'
