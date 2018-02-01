#!/usr/bin/env bash
set -eu
set -o nounset

PLUGINS_DIR="$DRONE_DIR/.sbt/1.0/plugins"
mkdir -p "$PLUGINS_DIR"
PLUGINS_FILE="$PLUGINS_DIR/coursier.sbt"

add_coursier() {
  echo "Adding coursier to the global plugins directory..."
  echo 'addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0")' >> "$PLUGINS_FILE"
}

if [[ -e "$PLUGINS_FILE" ]]; then
  PLUGINS=$(cat "$PLUGINS_FILE")
  if [[ "$PLUGINS" != *"coursier"* ]]; then
    add_coursier
  fi
else
  add_coursier
fi
