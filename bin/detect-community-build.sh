#!/usr/bin/env bash

set -e

MONITOR_REPO="scalacenter/bloop"
PR="$DRONE_PULL_REQUEST"

pr_has_label () {
  curl --silent https://api.github.com/repos/"$MONITOR_REPO"/issues/"$PR"/labels | jq -e '. | map(select( .name == "'"$1"'" )) | .[].name' > /dev/null 2>&1
}

if git log -1 --pretty=%B --no-merges | grep FORCE_TEST_RESOURCES_GENERATION > /dev/null 2>&1; then
  export FORCE_TEST_RESOURCES_GENERATION=true
fi

if [[ "$PR" ]]; then
  if pr_has_label "community build"; then
    export RUN_COMMUNITY_BUILD=true
    echo "The community build will run for pull request $PR."
  elif [[ "$PIPELINE_COMMUNITY_BUILD" == "true" ]]; then
    if pr_has_label "build pipelining"; then
      export RUN_COMMUNITY_BUILD=true
      echo "The pipelined community build will run for pull request $PR."
    else
      echo "The pipelined community build will not run for pull request $PR."
    fi
  else
    echo "The community build will not run for pull request $PR."
  fi
elif [[ "$DRONE_TAG" ]]; then
  export RUN_COMMUNITY_BUILD=true
  echo "The community build will run for the $DRONE_TAG release."
else
  echo "The DRONE_PULL_REQUEST environment variable was not set."
fi
