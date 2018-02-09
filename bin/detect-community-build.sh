#!/usr/bin/env bash

set -e

PR="$DRONE_PULL_REQUEST"
if [[ "$PR" ]]; then
  MONITOR_REPO="scalacenter/bloop"
  if curl --silent https://api.github.com/repos/"$MONITOR_REPO"/issues/"$PR"/labels | jq -e '.[].name | contains("community-build")'; then
    echo "The community build will be run for this pull request."
    export RUN_COMMUNITY_BUILD=true
  fi
fi
