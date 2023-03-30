#!/usr/bin/env bash
set -e

scalafmt "$@"

for dir in *; do
  if [ -f "$dir/.scalafmt.conf" ]; then
    ( cd "$dir" && scalafmt "$@" )
  fi
done
