#!/usr/bin/env bash

set -e

MONITOR_REPO="scalacenter/bloop"
PR="$DRONE_PULL_REQUEST"

if [[ "$PR" ]]; then
  if curl --silent https://api.github.com/repos/"$MONITOR_REPO"/issues/"$PR"/labels | jq -e '. | map(select( .name == "community-build" )) | .[].name' > /dev/null 2>&1 ; then
    export RUN_COMMUNITY_BUILD=true
    echo "The community build will run for this pull request."
  else
    echo "The community build will not run for this pull request."
  fi
elif [[ "$DRONE_TAG" ]]; then
  export RUN_COMMUNITY_BUILD=true
  echo "The community build will run for the $DRONE_TAG release."
else
  echo "The DRONE_PULL_REQUEST environment variable was not set."
fi
