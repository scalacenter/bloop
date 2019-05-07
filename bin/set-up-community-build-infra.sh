#!/usr/bin/env bash

set -e

# Create in case they are not created
mkdir -p /drone/.dodo
mkdir -p /drone/.buildpress

ln -s /drone/.dodo /root/.dodo
ln -s /drone/.buildpress /root/.buildpress
