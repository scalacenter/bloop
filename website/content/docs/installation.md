+++
description = "How to install Bloop on your machine"
title = "The installation process"
date = "2018-02-08T16:35:00+01:00"
draft = false
weight = 1
bref = "Here are all the instructions to install Bloop"
toc = true
+++

### Instructions for Mac OS

Installing Bloop with [Homebrew](https://brew.sh) is the easiest and fastest way to install Bloop on
Mac OS.

We publish an updated formula along with every release of Bloop. You can install it using:

```sh
$ brew install scalacenter/bloop/bloop
```

The formula will also take care of installing a property list (`plist`) that will take care of
starting the Bloop server when you log onto your machine. This way, you don't have to think about
starting it yourself.

If you don't want the Bloop server to start automatically, you can disable it: 

```sh
$ launchctl disable bloop
```

If you want to have it start automatically again, type:

```sh
$ launchctl enable bloop
```

Go to [Next steps]({{< ref "#next-steps" >}}) to configure Bloop and start using it.

### Instructions for other platforms

Install bloop in other platforms (Windows, Unix, \*bsd) via our python script:

```sh
$ curl -L https://github.com/scalacenter/bloop/releases/download/v1.0.0-M5/install.py | python2
```

<span class="label warning">Note</span> that you may need to update the version
in the URL. Check the last released version in [Bloop GitHub releases]({{<
githubrepo >}}/releases).

#### Installing in a different bloop folder

By default, the script installs bloop binaries in a `.bloop` directory in your
`$HOME` folder. You can change that with the `--dest` option:

```sh
$ ./install.py --dest ~/bin/bloop
```

Go to [Next steps]({{< ref "#next-steps" >}}) to configure Bloop and start using it.

### Installing a development version

The installation script can also be used to install a development version of Bloop. The [installation
script]({{< githubrepo >}}) can be downloaded directly from our repository.

This installation script requires more information to install Bloop:

 - The exact version to install. We publish artifacts for every commit on `master`. Look at the CI
   logs to figure out the exact version number.
 - The SHA of the commit to use to get the Nailgun client. Look at which commit points our `nailgun`
   submodule at a given point in time to find that information.

Pass this information to the installation script:

```sh
$ ./install.py -v $BLOOP_VERSION -n $NAILGUN_COMMIT
```

### Next steps

Once bloop has been successfully installed on your machine, you can move to build-tool specific part
of the installation:

 - [Getting started with sbt]({{< ref "getting-started-sbt.md" >}})
 - [Getting started with Maven]({{< ref "getting-started-maven.md" >}})
