#!/usr/bin/env bash
set -eu
set -o nounset

find "/root/.ivy2/cache"     -name "ivydata-*.properties" -print -delete
find "/root/.coursier"       -name "ivydata-*.properties" -print -delete
find "/root/.sbt"            -name "*.lock"               -print -delete
