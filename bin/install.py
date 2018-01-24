#!/usr/bin/env python2

import argparse
import urllib
import os
from os.path import expanduser, isdir, isfile, join
from subprocess import CalledProcessError, check_call
import sys

BLOOP_DEFAULT_INSTALLATION_TARGET = join(expanduser("~"), ".bloop")
BLOOP_LATEST_RELEASE = "no-tag-yet"
COURSIER_URL = "https://git.io/vgvpD"
NAILGUN_COMMIT = "cb9846daaea13eba068c812376f6c90478fcea79"
NAILGUN_CLIENT_URL = "https://raw.githubusercontent.com/scalacenter/nailgun/%s/pynailgun/ng.py" % NAILGUN_COMMIT

parser = argparse.ArgumentParser(description="Installation script for Bloop.")
parser.add_argument(
    '-d',
    '--dest',
    default=BLOOP_DEFAULT_INSTALLATION_TARGET,
    help="Where to install Bloop, defaults to %s" %
    BLOOP_DEFAULT_INSTALLATION_TARGET)
parser.add_argument(
    '-v',
    '--version',
    default=BLOOP_LATEST_RELEASE,
    help="Version of Bloop to install, defaults to %s" % BLOOP_LATEST_RELEASE)
args = parser.parse_args()

BLOOP_INSTALLATION_TARGET = args.dest
BLOOP_VERSION = args.version

BLOOP_COURSIER_TARGET = join(BLOOP_INSTALLATION_TARGET, "coursier")
BLOOP_SERVER_TARGET = join(BLOOP_INSTALLATION_TARGET, "bloop-server")
BLOOP_SHELL_TARGET = join(BLOOP_INSTALLATION_TARGET, "bloop-shell")
BLOOP_CLIENT_TARGET = join(BLOOP_INSTALLATION_TARGET, "bloop-ng.py")

BLOOP_ARTIFACT = "ch.epfl.scala:bloop-frontend_2.12:%s" % BLOOP_VERSION


def download_and_install(url, target):
    try:
        urllib.urlretrieve(url, target)
        os.chmod(target, 0755)
    except IOError:
        print "Couldn't download %s, please try again." % url
        sys.exit(1)


def coursier_bootstrap(target, main):
    try:
        check_call([
            "java", "-jar", BLOOP_COURSIER_TARGET, "bootstrap", BLOOP_ARTIFACT,
            "-o", target, "--standalone", "--main", main
        ])
    except CalledProcessError as e:
        print "Coursier couldn't create %s. Please report an issue." % target
        print "Command: %s" % e.cmd
        print "Return code: %d" % e.returncode
        sys.exit(e.returncode)


if not isdir(BLOOP_INSTALLATION_TARGET):
    os.makedirs(BLOOP_INSTALLATION_TARGET)

if not isfile(BLOOP_COURSIER_TARGET):
    download_and_install(COURSIER_URL, BLOOP_COURSIER_TARGET)

if not isfile(BLOOP_SERVER_TARGET):
    coursier_bootstrap(BLOOP_SERVER_TARGET, "bloop.Server")
    print "Installed bloop server in '%s'" % BLOOP_SERVER_TARGET

if not isfile(BLOOP_SHELL_TARGET):
    coursier_bootstrap(BLOOP_SHELL_TARGET, "bloop.Bloop")
    print "Installed bloop shell in '%s'" % BLOOP_SHELL_TARGET

if not isfile(BLOOP_CLIENT_TARGET):
    download_and_install(NAILGUN_CLIENT_URL, BLOOP_CLIENT_TARGET)
    print "Installed bloop client in '%s'" % BLOOP_CLIENT_TARGET
