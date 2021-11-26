#!/usr/bin/env bash
set -e

cd nailgun
sbt nailgun-server/publishLocal
