#!/usr/bin/env bash
set -e

cd frontend/src/test/resources/

for d in *; do
  if test -f "$d/build.sbt"; then
    cd "$d"
    sbt bloopInstall
    cd -
  fi
done
