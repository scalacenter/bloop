#!/usr/bin/env bash
set -e

cd frontend/src/test/resources/

for d in *; do
  if test -f "$d/build.sbt"; then
    cd "$d"
    sbt "-DbloopVersion=1.4.11-9-827a32e7" bloopInstall
    cd -
  fi
done
