#!/usr/bin/env bash
set -o nounset

if git log -1 --pretty=%B --no-merges | grep DOCS > /dev/null 2>&1; then
  echo "Found DOCS in commit message, the CI will only build the docs site."
  if ! sbt docs/run; then
    exit 1
  fi
else
  if ! sbt "$@"; then
    exit 1
  fi
fi
