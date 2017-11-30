#!/usr/bin/env sh
test -e ~/.coursier/coursier || ( \
  mkdir -p ~/.coursier && \
  curl -Lso ~/.coursier/coursier https://git.io/vgvpD && \
  chmod +x ~/.coursier/coursier \
)

test -e ~/.bloop/bloop-server || ( \
  mkdir -p ~/.bloop && \
  ~/.coursier/coursier bootstrap ch.epfl.scala:bloop_2.12:XXXXX \
    -o ~/.bloop/bloop-server \
    --standalone \
    --main bloop.Server && \
  echo "Installed bloop server in '~/.bloop/bloop-server'"
)

test -e ~/.bloop/ng.py || ( \
  mkdir -p ~/.bloop && \
  curl -Ls https://raw.githubusercontent.com/scalacenter/nailgun/zinc-nailgun/pynailgun/ng.py \
  -o ~/.bloop/bloop-ng.py && \
  chmod +x ~/.bloop/bloop-ng.py && \
  echo "Installed bloop client in '~/.bloop/bloop-ng.py'"
)

