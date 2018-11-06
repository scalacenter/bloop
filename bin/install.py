#!/usr/bin/env python

from __future__ import print_function

import argparse
import os
from os.path import expanduser, isdir, isfile, join, dirname, abspath
from subprocess import CalledProcessError, check_call
import sys
import re
from shutil import copyfileobj, copyfile
from urlparse import urlparse

IS_PY2 = sys.version[0] == '2'
if IS_PY2:
    from urllib2 import urlopen as urlopen
else:
    from urllib.request import urlopen as urlopen

# INSERT_INSTALL_VARIABLES
BLOOP_DEFAULT_INSTALLATION_TARGET = join(expanduser("~"), ".bloop")

# Check whether this script has been customized to allow installation without passing args
# `BLOOP_VERSION` and `NAILGUN_COMMIT` will be defined at the beginning of this script
# in our release process.
CUSTOMIZED_SCRIPT = False
try:
    BLOOP_VERSION
    NAILGUN_COMMIT
    COURSIER_VERSION
    CUSTOMIZED_SCRIPT = True
except NameError:
    BLOOP_VERSION = None
    NAILGUN_COMMIT = None
    COURSIER_VERSION = None

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
parser.add_argument(
    '-c',
    '--coursier',
    required=not CUSTOMIZED_SCRIPT,
    default=COURSIER_VERSION,
    help="Version number for the coursier client if not already installed.")
parser.add_argument(
    '--ivy-home',
    help="Tell the location of the ivy home.")
parser.add_argument(
    '--bloop-home',
    help="Tell the location of the ivy home.")
args = parser.parse_args()
is_local = args.ivy_home is not None

BLOOP_INSTALLATION_TARGET = args.dest
BLOOP_VERSION = args.version
NAILGUN_COMMIT = args.nailgun
ZSH_COMPLETION_DIR = join(BLOOP_INSTALLATION_TARGET, "zsh")
BASH_COMPLETION_DIR = join(BLOOP_INSTALLATION_TARGET, "bash")
SYSTEMD_SERVICE_DIR = join(BLOOP_INSTALLATION_TARGET, "systemd")
XDG_DIR = join(BLOOP_INSTALLATION_TARGET, "xdg")
COURSIER_URL = "https://github.com/coursier/coursier/raw/v" + args.coursier + "/coursier"

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
    except IOError as e:
        print(e)
        print("Couldn't download %s, please try again." % url)
        sys.exit(1)

def download_and_install(url, target, permissions=0o644):
    download(url, target)
    os.chmod(target, permissions)

def copy_and_install(origin, target, permissions=0o644):
    copyfile(origin, target)
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
        from os.path import expanduser
        ivy_home = expanduser("~") + "/.ivy2/"
        https = []
        http = []
        if "http_proxy" in os.environ:
            http_proxy =  urlparse(os.environ['http_proxy'])
            http = [
                "-Dhttp.proxyHost="+http_proxy.hostname,
                "-Dhttp.proxyPort="+str(http_proxy.port)
                ]

        if "https_proxy" in os.environ:
            https_proxy =  urlparse(os.environ['https_proxy'])
            https = [
                "-Dhttps.proxyHost="+https_proxy.hostname,
                "-Dhttps.proxyPort="+str(https_proxy.port)
                ]

        if is_local:
            check_call(["java"] + http + https + ["-Divy.home=" + args.ivy_home, "-jar", BLOOP_COURSIER_TARGET, "bootstrap", BLOOP_ARTIFACT,
                "-r", "bintray:scalameta/maven",
                "-r", "bintray:scalacenter/releases",
                "-o", target, "-f", "--standalone", "--main", main
            ])
        else:
            check_call(["java"] + http + https + ["-jar", BLOOP_COURSIER_TARGET, "bootstrap", BLOOP_ARTIFACT,
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

# Install coursier always because there is no way to tell when the coursier version changes
print("Downloading Bloop's coursier version, this may take some seconds...")
download_and_install(COURSIER_URL, BLOOP_COURSIER_TARGET, 0o755)

coursier_bootstrap(BLOOP_SERVER_TARGET, "bloop.Server")
print("Installed bloop server in '%s'" % BLOOP_SERVER_TARGET)

download_and_install(NAILGUN_CLIENT_URL, BLOOP_CLIENT_TARGET, 0o755)
print("Installed bloop client in '%s'" % BLOOP_CLIENT_TARGET)

if is_local:
    if args.bloop_home is None:
        print("Fatal error: --bloop-home expected for local installation")
        sys.exit(1)

    # The bloop rb is created under the relative path 'frontend/target/Bloop.rb'
    local_zsh = join(join(join(args.bloop_home, "etc"), "zsh"), "_bloop")
    local_bash = join(join(join(args.bloop_home, "etc"), "bash"), "bloop")

    copy_and_install(local_zsh, ZSH_COMPLETION_TARGET, 0o755)
    print("Installed zsh completion in '%s'" % ZSH_COMPLETION_TARGET)

    copy_and_install(local_bash, BASH_COMPLETION_TARGET, 0o755)
    print("Installed Bash completion in '%s'" % BASH_COMPLETION_TARGET)
else:
    download_and_install(ZSH_COMPLETION_URL, ZSH_COMPLETION_TARGET, 0o755)
    print("Installed zsh completion in '%s'" % ZSH_COMPLETION_TARGET)

    download_and_install(BASH_COMPLETION_URL, BASH_COMPLETION_TARGET, 0o755)
    print("Installed Bash completion in '%s'" % BASH_COMPLETION_TARGET)

if not is_local:
    # Only copy these if we're not installing it locally
    download_and_install_template(SYSTEMD_SERVICE_URL, SYSTEMD_SERVICE_TARGET)
    print("Installed systemd service in '%s'" % SYSTEMD_SERVICE_TARGET)

    download_and_install_template(XDG_APPLICATION_URL, XDG_APPLICATION_TARGET, 0o755)
    print("Installed XDG desktop entry in '%s'" % XDG_APPLICATION_TARGET)

    download_and_install(XDG_ICON_URL, XDG_ICON_TARGET)
    print("Installed icon in '%s'" % XDG_ICON_TARGET)
