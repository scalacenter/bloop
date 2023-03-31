#!/usr/bin/env bash
set -e

if [[ "$OSTYPE" != "msys" ]]; then
  echo "Warning: this script is intended to be run on Windows" 1>&2
fi

./mill.bat -i ci.copyJvm --dest jvm
export JAVA_HOME="$(pwd -W | sed 's,/,\\,g')\\jvm"
export GRAALVM_HOME="$JAVA_HOME"
export PATH="$(pwd)/bin:$PATH"
echo "PATH=$PATH"
./mill.bat -i "cli.writeNativeImageScript" generate-native-image.bat ""
./generate-native-image.bat
./mill.bat -i "cli.copyToArtifacts" artifacts/
