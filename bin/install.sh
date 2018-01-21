#!/usr/bin/env sh

BLOOP_SPECIFIED_VERSION=$1
BLOOP_LATEST_RELEASE=XXX # We have no release yet :(
BLOOP_VERSION=${BLOOP_SPECIFIED_VERSION:-$BLOOP_LATEST_RELEASE}

test -e ~/.coursier/coursier || ( \
  mkdir -p ~/.coursier && \
  curl -Lso ~/.coursier/coursier https://git.io/vgvpD && \
  chmod +x ~/.coursier/coursier \
)

test -e ~/.bloop/bloop-server || ( \
  mkdir -p ~/.bloop && \
  ~/.coursier/coursier bootstrap ch.epfl.scala:bloop-frontend_2.12:$BLOOP_VERSION \
    -o ~/.bloop/bloop-server \
    --standalone \
    --main bloop.Server && \
  echo "Installed bloop server in '~/.bloop/bloop-server'"
)

test -e ~/.bloop/bloop-shell || ( \
  mkdir -p ~/.bloop && \
  ~/.coursier/coursier bootstrap ch.epfl.scala:bloop-frontend_2.12:$BLOOP_VERSION \
    -o ~/.bloop/bloop-shell \
    --standalone \
    --main bloop.Bloop && \
  echo "Installed bloop shell in '~/.bloop/bloop-shell'"
)

test -e ~/.bloop/bloop-ng.py || ( \
  mkdir -p ~/.bloop && \
  curl -Ls https://raw.githubusercontent.com/scalacenter/nailgun/sync-19-01-2018/pynailgun/ng.py \
  -o ~/.bloop/bloop-ng.py && \
  chmod +x ~/.bloop/bloop-ng.py && \
  echo "Installed bloop client in '~/.bloop/bloop-ng.py'"
)

