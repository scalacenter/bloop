#!/usr/bin/env bash
set -o nounset

if [[ "$GITHUB_EVENT_NAME" == "tag" ]]; then
  if ! sbt "$@"; then
    exit 1
  fi
fi
