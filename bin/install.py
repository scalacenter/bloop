#!/usr/bin/env python

from __future__ import print_function

import argparse
import os
from os.path import expanduser, isdir, isfile, join
from subprocess import CalledProcessError, check_call
import sys

IS_PY2 = sys.version[0] == '2'
if IS_PY2:
    from urllib import urlretrieve as urlretrieve
else:
    from urllib.request import urlretrieve as urlretrieve

# INSERT_INSTALL_VARIABLES
BLOOP_DEFAULT_INSTALLATION_TARGET = join(expanduser("~"), ".bloop")
COURSIER_URL = "https://git.io/vgvpD"

# Check whether this script has been customized to allow installation without passing args
# `BLOOP_VERSION` and `NAILGUN_COMMIT` will be defined at the beginning of this script
# in our release process.
CUSTOMIZED_SCRIPT = False
try:
    BLOOP_VERSION
    NAILGUN_COMMIT
    CUSTOMIZED_SCRIPT = True
except NameError:
    BLOOP_VERSION = None
    NAILGUN_COMMIT = None

parser = argparse.ArgumentParser(description="Installation script for Bloop.")
parser.add_argument(
    '-d',
    '--dest',
    default=BLOOP_DEFAULT_INSTALLATION_TARGET,
    help="Where to install Bloop, defaults to %s" % os.getcwd())
parser.add_argument(
    '-v',
    '--version',
    required=not CUSTOMIZED_SCRIPT,
    default=BLOOP_VERSION,
    help="Version of Bloop to install")
parser.add_argument(
    '-n',
    '--nailgun',
    required=not CUSTOMIZED_SCRIPT,
    default=NAILGUN_COMMIT,
    help="Commit hash of the Nailgun client ot use")
args = parser.parse_args()

BLOOP_INSTALLATION_TARGET = args.dest
BLOOP_VERSION = args.version
NAILGUN_COMMIT = args.nailgun

NAILGUN_CLIENT_URL = "https://raw.githubusercontent.com/scalacenter/nailgun/%s/pynailgun/ng.py" % NAILGUN_COMMIT
BLOOP_COURSIER_TARGET = join(BLOOP_INSTALLATION_TARGET, "blp-coursier")
BLOOP_SERVER_TARGET = join(BLOOP_INSTALLATION_TARGET, "blp-server")
BLOOP_CLIENT_TARGET = join(BLOOP_INSTALLATION_TARGET, "bloop")

BLOOP_ARTIFACT = "ch.epfl.scala:bloop-frontend_2.12:%s" % BLOOP_VERSION

def download_and_install(url, target):
    try:
        urlretrieve(url, target)
        os.chmod(target, 0o755)
    except IOError:
        print("Couldn't download %s, please try again." % url)
        sys.exit(1)

def coursier_bootstrap(target, main):
    try:
        check_call([
            "java", "-jar", BLOOP_COURSIER_TARGET, "bootstrap", BLOOP_ARTIFACT,
            "-r", "bintray:scalameta/maven",
            "-r", "bintray:scalacenter/releases",
            "-o", target, "-f", "--standalone", "--main", main
        ])
    except CalledProcessError as e:
        print("Coursier couldn't create %s. Please report an issue." % target)
        print("Command: %s" % e.cmd)
        print("Return code: %d" % e.returncode)
        sys.exit(e.returncode)

if not isdir(BLOOP_INSTALLATION_TARGET):
    os.makedirs(BLOOP_INSTALLATION_TARGET)

if not isfile(BLOOP_COURSIER_TARGET):
    download_and_install(COURSIER_URL, BLOOP_COURSIER_TARGET)

coursier_bootstrap(BLOOP_SERVER_TARGET, "bloop.Server")
print("Installed bloop server in '%s'" % BLOOP_SERVER_TARGET)

download_and_install(NAILGUN_CLIENT_URL, BLOOP_CLIENT_TARGET)
print("Installed bloop client in '%s'" % BLOOP_CLIENT_TARGET)
