#!/usr/bin/env python

from __future__ import print_function

import argparse
import os
from os.path import expanduser, isdir, isfile, join
from subprocess import CalledProcessError, check_call
import sys
import re
from shutil import copyfileobj

IS_PY2 = sys.version[0] == '2'
if IS_PY2:
    from urllib2 import urlopen as urlopen
else:
    from urllib.request import urlopen as urlopen

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
ZSH_COMPLETION_DIR = join(BLOOP_INSTALLATION_TARGET, "zsh")
BASH_COMPLETION_DIR = join(BLOOP_INSTALLATION_TARGET, "bash")
SYSTEMD_SERVICE_DIR = join(BLOOP_INSTALLATION_TARGET, "systemd")
XDG_DIR = join(BLOOP_INSTALLATION_TARGET, "xdg")

# If this is not a released version of Bloop, we need to extract the commit SHA
# to know how to download the completion and startup scripts.
# If we can't get the SHA, just download from master.
if CUSTOMIZED_SCRIPT:
    ETC_VERSION = "v" + BLOOP_VERSION
else:
    pattern = '(?:.+?)-([0-9a-f]{8})(?:\+\d{8}-\d{4})?'
    matches = re.search(pattern, BLOOP_VERSION)
    if matches is not None:
        ETC_VERSION = matches.group(1)
    else:
        ETC_VERSION = "master"

NAILGUN_CLIENT_URL = "https://raw.githubusercontent.com/scalacenter/nailgun/%s/pynailgun/ng.py" % NAILGUN_COMMIT
ZSH_COMPLETION_URL = "https://raw.githubusercontent.com/scalacenter/bloop/%s/etc/zsh/_bloop" % ETC_VERSION
BASH_COMPLETION_URL = "https://raw.githubusercontent.com/scalacenter/bloop/%s/etc/bash/bloop" % ETC_VERSION
SYSTEMD_SERVICE_URL = "https://raw.githubusercontent.com/scalacenter/bloop/%s/etc/systemd/bloop.service" % ETC_VERSION
XDG_APPLICATION_URL = "https://raw.githubusercontent.com/scalacenter/bloop/%s/etc/xdg/bloop.desktop" % ETC_VERSION
XDG_ICON_URL = "https://raw.githubusercontent.com/scalacenter/bloop/%s/etc/xdg/bloop.png" % ETC_VERSION
BLOOP_COURSIER_TARGET = join(BLOOP_INSTALLATION_TARGET, "blp-coursier")
BLOOP_SERVER_TARGET = join(BLOOP_INSTALLATION_TARGET, "blp-server")
BLOOP_CLIENT_TARGET = join(BLOOP_INSTALLATION_TARGET, "bloop")
ZSH_COMPLETION_TARGET = join(ZSH_COMPLETION_DIR, "_bloop")
BASH_COMPLETION_TARGET = join(BASH_COMPLETION_DIR, "bloop")
SYSTEMD_SERVICE_TARGET = join(SYSTEMD_SERVICE_DIR, "bloop.service")
XDG_APPLICATION_TARGET = join(XDG_DIR, "bloop.desktop")
XDG_ICON_TARGET = join(XDG_DIR, "bloop.png")

BLOOP_ARTIFACT = "ch.epfl.scala:bloop-frontend_2.12:%s" % BLOOP_VERSION

BUFFER_SIZE = 4096

def download(url, target):
    try:
        socket = urlopen(url)
        with open(target, "wb") as file:
            copyfileobj(socket, file, BUFFER_SIZE)
        socket.close()
    except IOError:
        print("Couldn't download %s, please try again." % url)
        sys.exit(1)

def download_and_install(url, target, permissions=0o644):
    download(url, target)
    os.chmod(target, permissions)

def replace_template_variables(template):
    return template.replace("__BLOOP_INSTALLATION_TARGET__", BLOOP_INSTALLATION_TARGET)

def download_and_install_template(url, target, permissions=0o644):
    template_target = target + ".tmp"
    download(url, template_target)
    with open(template_target, "r") as template_file, open(target, "w") as output_file:
        template = template_file.read()
        output = replace_template_variables(template)
        output_file.write(output)
    os.remove(template_target)
    os.chmod(target, permissions)

def coursier_bootstrap(target, main):
    try:
        check_call([
            "java", "-jar", BLOOP_COURSIER_TARGET, "bootstrap", BLOOP_ARTIFACT,
            "-r", "bintray:scalameta/maven",
            "-r", "bintray:scalacenter/releases",
            "-r", "https://oss.sonatype.org/content/repositories/staging",
            "-o", target, "-f", "--standalone", "--main", main
        ])
    except CalledProcessError as e:
        print("Coursier couldn't create %s. Please report an issue." % target)
        print("Command: %s" % e.cmd)
        print("Return code: %d" % e.returncode)
        sys.exit(e.returncode)

def makedir(directory):
    if not isdir(directory):
        os.makedirs(directory)

makedir(BLOOP_INSTALLATION_TARGET)
makedir(ZSH_COMPLETION_DIR)
makedir(BASH_COMPLETION_DIR)
makedir(SYSTEMD_SERVICE_DIR)
makedir(XDG_DIR)

if not isfile(BLOOP_COURSIER_TARGET):
    download_and_install(COURSIER_URL, BLOOP_COURSIER_TARGET, 0o755)

coursier_bootstrap(BLOOP_SERVER_TARGET, "bloop.Server")
print("Installed bloop server in '%s'" % BLOOP_SERVER_TARGET)

download_and_install(NAILGUN_CLIENT_URL, BLOOP_CLIENT_TARGET, 0o755)
print("Installed bloop client in '%s'" % BLOOP_CLIENT_TARGET)

download_and_install(ZSH_COMPLETION_URL, ZSH_COMPLETION_TARGET, 0o755)
print("Installed zsh completion in '%s'" % ZSH_COMPLETION_TARGET)

download_and_install(BASH_COMPLETION_URL, BASH_COMPLETION_TARGET, 0o755)
print("Installed Bash completion in '%s'" % BASH_COMPLETION_TARGET)

download_and_install_template(SYSTEMD_SERVICE_URL, SYSTEMD_SERVICE_TARGET)
print("Installed systemd service in '%s'" % SYSTEMD_SERVICE_TARGET)

download_and_install_template(XDG_APPLICATION_URL, XDG_APPLICATION_TARGET, 0o755)
print("Installed XDG desktop entry in '%s'" % XDG_APPLICATION_TARGET)

download_and_install(XDG_ICON_URL, XDG_ICON_TARGET)
print("Installed icon in '%s'" % XDG_ICON_TARGET)
