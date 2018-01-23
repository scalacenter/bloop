#!/usr/bin/env python2

import urllib
import os
from os.path import expanduser, isdir, isfile, join
from subprocess import call
import sys

BLOOP_LATEST_RELEASE      = "no-tag-yet"
BLOOP_INSTALLATION_TARGET = join(expanduser("~"), ".bloop")
BLOOP_COURSIER_TARGET     = join(BLOOP_INSTALLATION_TARGET, "coursier")
BLOOP_SERVER_TARGET       = join(BLOOP_INSTALLATION_TARGET, "bloop-server")
BLOOP_SHELL_TARGET        = join(BLOOP_INSTALLATION_TARGET, "bloop-shell")
BLOOP_CLIENT_TARGET       = join(BLOOP_INSTALLATION_TARGET, "bloop-ng.py")

COURSIER_URL = "https://git.io/vgvpD"

NAILGUN_COMMIT     = "0927946db663927151a53fe3b365b2655613db86"
NAILGUN_CLIENT_URL = "https://raw.githubusercontent.com/scalacenter/nailgun/%s/pynailgun/ng.py" % NAILGUN_COMMIT

BLOOP_VERSION = ""
if len(sys.argv) >= 2:
    BLOOP_VERSION = sys.argv[1]
else:
    BLOOP_VERSION = BLOOP_LATEST_RELEASE

BLOOP_ARTIFACT = "ch.epfl.scala:bloop-frontend_2.12:%s" % BLOOP_VERSION

def download_and_install(url, target):
    urllib.urlretrieve(url, target)
    os.chmod(target, 0755)

def coursier_bootstrap(target, main):
    call(["java", "-jar", BLOOP_COURSIER_TARGET, "bootstrap", BLOOP_ARTIFACT, "-o", target, "--standalone", "--main", main])

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

