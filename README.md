<p align="center">
  <img src="./misc/logo.svg" alt="logo" width="30%">
</p>

<p align="center">
Bloop gives you <b>fast</b> edit/compile/test workflows for <b>Scala</b>.
</p>

[![GitHub tag](https://img.shields.io/github/tag/scalacenter/bloop.svg)]()
[![Build Status](https://ci.scala-lang.org/api/badges/scalacenter/bloop/status.svg)](https://ci.scala-lang.org/scalacenter/bloop)
[![Join the chat](https://badges.gitter.im/scalacenter/bloop.svg)](https://gitter.im/scalacenter/bloop)

## Goals

To understand the goals of bloop, we strongly encourage you to read [this Scala blog
post][bloop-release-post].

Bloop is a command-line tool for fast edit/compile/test workflows. Its primary
goal is to compile and test your project as fast as possible, offering a snappy
developer experience. Bloop does not aim to replace your stock build tool, but
rather complement it.

*Disclaimer*: Bloop is in beta, that means that you should not expect
everything to make sense and you should be ready to see unexpected behaviours.
We're working hard to quickly improve it, and we encourage you to update master
on a daily basis if you start using the tool.

## Installation

### Building Bloop locally

Bloop is not released yet. To publish bloop locally, you'll need to clone this repository and use
sbt:

```sh
$ git clone --recursive https://github.com/scalacenter/bloop.git
$ cd bloop
$ sbt install
$ VERSION=$(sbt -no-colors frontend/version | tail -n1 | cut -d' ' -f2)
$ NAILGUN=$(git --git-dir=nailgun/.git rev-parse HEAD)
$ bin/install.py --dest $HOME/.bloop --nailgun $NAILGUN --version $VERSION
```

The script will create the executables `~/.bloop/blp-server`, `~/.bloop/blp-shell`
and  `~/.bloop/bloop`:

 - `blp-server` is the Bloop server
 - `bloop` is the Bloop client
 - `blp-shell` is a shell that you can use while the Nailgun integration is experimental

We describe how to use Bloop with the experimental Nailgun integration. The shell will be removed in
the next versions of Bloop: don't rely on it.

To be able to start the Bloop server and client without specifying the full path to the commands,
we suggest that you add the following to your shell configuration:

```sh
export PATH="$PATH:~/.bloop"
```

The next sections assume that you've added that line to your profile, and reloaded your shell.

### Installing a released version of Bloop

**Bloop hasn't been released yet, so these instructions won't work!**

#### On Mac OS

##### With Homebrew
The easiest solution is to install Bloop using Homebrew:

```sh
$ brew install scalacenter/bloop/bloop
```

Homebrew can take care of starting Bloop automatically when you log into your machine. This is
optional, but we recommend it:

```sh
$ brew services start bloop
```

##### Manually
You can also use our installation script to install Bloop:

```sh
$ curl https://raw.githubusercontent.com/scalacenter/bloop/no-tag-yet/bin/install.py | python2
```

The script will create the executables `~/.bloop/blp-server`, `~/.bloop/BLOOP_SHELL_CMD#`
and  `~/.bloop/bloop`:

 - `blp-server` is the Bloop server
 - `bloop` is the Bloop client
 - `blp-shell` is a shell that you can use while the Nailgun integration is experimental

We describe how to use Bloop with the experimental Nailgun integration. The shell will be removed in
the next versions of Bloop: don't rely on it.

To be able to start the Bloop server and client without specifying the full path to the commands,
we suggest that you add the following to your shell configuration:

```sh
export PATH="$PATH:~/.bloop"
```

The next sections assume that you've added that line to your profile, and reloaded your shell.

## How to use

### Generate the configuration files

First, we'll need to generate Bloop's configuration files for your project. To
do this, add the following sbt plugin in `project/plugins.sbt` in your project:
(While Bloop isn't released, you'll need to replace `no-tag-yet` with the version you
copy-pasted earlier)

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "no-tag-yet")
```

You can then use sbt to generate the configuration:

```sh
$ sbt installBloop
```

### Start the server

If you have installed Bloop using Homebrew, you can start the server with:

```sh
$ brew services start bloop
```

Otherwise, you can start it with:

```sh
$ blp-server &
```

Note that you only need to start the server once on your machine, and you can use it with as many
projects as you want, simultaneously.

### Command examples

```
$ bloop projects # show the projects that are loaded
$ bloop compile -p my-project # compile my-project
$ bloop test -p my-project-test # run the tests on my-project
$ bloop exit # shuts the compilation server down
```

## Command reference

```sh
$ bloop --help
Usage: bloop [options] [command] [command-options]


Available commands: about, clean, compile, help, projects, test
Type `bloop 'command' --help` for help on an individual command
```

## Tab-completion

Bloop supports tab-completion on ZSH. To install command completion on ZSH,
copy the [completions][zsh-completions] file to an existing completions
directory, if it exists.

If not, you can create it, and update your `.zshrc` script as follows:

```sh
$ mkdir -p ~/.zsh/completion
$ cp etc/_bloop ~/.zsh/completion/
$ echo 'fpath=(~/.zsh/completion $fpath)' >> ~/.zshrc
$ echo 'autoload -Uz compinit ; compinit' >> ~/.zshrc
```

Closing and reopening your shell should make bloop tab-completions available.

[installation-script]: https://raw.githubusercontent.com/scalacenter/bloop/master/bin/install.sh
[zsh-completions]: https://raw.githubusercontent.com/scalacenter/bloop/master/etc/_bloop
[bloop-release-post]: http://www.scala-lang.org/blog/2017/11/30/bloop-release.html
