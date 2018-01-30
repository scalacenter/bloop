#!/usr/bin/env sh
# Taken from lampepfl/dotty
set -eux

# if build is PR rebase on top of target branch
if [ "$DRONE_BUILD_EVENT" = "pull_request" ]; then
  git config user.email "bloopoid@trashmail.ws"
  git config user.name "Bloopoid"
  git pull "$DRONE_REMOTE_URL" "$DRONE_BRANCH"
fi

# clone submodules in parallel
git submodule update --init --recursive --jobs 4 --recommend-shallow
