#!/usr/bin/env bash
set -o nounset

if git status | grep cli-reference > /dev/null 2>&1; then
  echo "Found dirty changes in cli-reference!"
  echo "Update docs/cli/reference.md with 'frontend/runMain bloop.util.CommandsDocGenerator --out ../docs/cli/reference.md' or similar bloop command"
  exit 1
fi
