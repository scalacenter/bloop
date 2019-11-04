#!/usr/bin/env bash
set -o nounset

mkdir "$HOME/.gnupg"
echo "$PGP_PUBLIC_KEY" > "$HOME/.gnupg/pubring.asc"
echo "$PGP_PRIVATE_KEY" > "$HOME/.gnupg/secring.asc"