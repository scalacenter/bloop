#!/usr/bin/env bash
set -o nounset

# I don't trust $DRONE_COMMIT_MESSAGE here, it's had problems in the future
if git log -1 --pretty=%B --no-merges | grep DOCS > /dev/null 2>&1; then
  echo "Ignoring sbt invocation, docs change was detected so we only update the docs site."
  sbt -sbt-dir /drone/.sbt/1.0 -sbt-boot /drone/.sbt/boot docs/makeSite
else
  sbt -sbt-dir /drone/.sbt/1.0 -sbt-boot /drone/.sbt/boot $@
  find "/root/.ivy2/cache"     -name "ivydata-*.properties" -print -delete
  find "/root/.coursier"       -name "ivydata-*.properties" -print -delete
  find "/root/.sbt"            -name "*.lock"               -print -delete
fi
