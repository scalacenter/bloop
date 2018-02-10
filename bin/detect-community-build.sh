#!/usr/bin/env bash

set -e

MONITOR_REPO="scalacenter/bloop"
PR="$DRONE_PULL_REQUEST"

if [[ "$PR" ]]; then
  if curl --silent https://api.github.com/repos/"$MONITOR_REPO"/issues/"$PR"/labels | jq -e '.[].name | contains("community-build")' > /dev/null 2>&1 ; then
    echo "The community build will be run for this pull request."
    exit 0
  else
    echo "The community build will not run for this pull request."
    exit 1
  fi
else
  echo "The PR environment variable was not set."
  exit 1
fi
