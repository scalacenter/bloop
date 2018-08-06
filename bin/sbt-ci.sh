#!/usr/bin/env bash
set -o nounset

# I don't trust $DRONE_COMMIT_MESSAGE here, it's had problems in the future
if git log -1 --pretty=%B --no-merges | grep DOCS > /dev/null 2>&1; then
  echo "Found DOCS in commit message, the CI will only update the docs site."
  if [[ "$DRONE_BUILD_EVENT" != "pull_request" && "$DRONE_BRANCH" == "master" ]]; then
    if ! sbt -sbt-dir /drone/.sbt/1.0 -sbt-boot /drone/.sbt/boot docs/makeSite docs/ghpagesPushSite; then
      exit 1
    fi
  else 
    if ! sbt -sbt-dir /drone/.sbt/1.0 -sbt-boot /drone/.sbt/boot docs/makeSite; then
      exit 1
    fi
  fi
else
  if ! sbt -sbt-dir /drone/.sbt/1.0 -sbt-boot /drone/.sbt/boot "$@"; then
    exit 1
  fi
  find "/root/.ivy2/cache"     -name "ivydata-*.properties" -print -delete
  find "/root/.coursier"       -name "ivydata-*.properties" -print -delete
  find "/root/.sbt"            -name "*.lock"               -print -delete
fi
