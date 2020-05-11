#!/usr/bin/env bash
set -o nounset

if [[ "$GITHUB_REF" = "refs/tags/"* ]]; then
  if ! sbt "$@"; then
    exit 1
  fi
fi
