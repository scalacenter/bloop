#!/usr/bin/env bash
set -e

cd frontend/src/test/resources/

for d in *; do
  if test -d "$d"; then
    cd "$d"
    sbt bloopInstall
    cd -
  fi
done
