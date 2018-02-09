+++
description = "How to install Bloop on your machine"
title = "Installation"
date = "2018-02-08T16:35:00+01:00"
draft = false
weight = 1
bref = "Here are all the instructions to install Bloop"
toc = true
+++

<h3 class="section-head" id="macos"><a href="#macos">Instructions for Mac OS</a></h3>

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

<h3 class="section-head" id="manual"><a href="#manual">Instructions for other platforms</a></h3>

We do not publish native packages for other platforms at the moment, but you can use our
Python installation script to install Bloop on your machine. The installation script has been
reported to work on major Linux distributions, Mac OS and Windows.

The installation script can be downloaded from [the Releases of Bloop]({{< githubrepo
>}}/releases).
Run it to install Bloop in your current working directory:

```sh
$ ./install.py
```

You can then move the generated binaries somewhere else on your disk. Optionally, you can also pass
a destination where the installation script should put the binaries:

```sh
$ ./install.py --dest ~/bin/bloop
```

Go to [Next steps]({{< ref "#next-steps" >}}) to configure Bloop and start using it.

<h3 class="section-head" id="development"><a href="#development">Installing a development version</a></h3>

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

<h3 class="section-head" id="next-steps"><a href="#next-steps">Next steps</a></h3>

Once bloop has been successfully installed on your machine, you can move to build-tool specific part
of the installation:

 - [Getting started with sbt]({{< ref "getting-started-sbt.md" >}})
 - [Getting started with Maven]({{< ref "getting-started-maven.md" >}})
