<p align="center">
  <img src="https://user-images.githubusercontent.com/2462974/32790850-d2eeffc2-c95f-11e7-8a3e-7032ca875ce3.png?style=centerme" alt="g38782" style="max-width:100%;">
</p>

<p align="center">
Bloop gives you fast edit/compile/test workflows for Scala.
</p>

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
$ sbt
> install
> frontend/version # copy this version number
$ bin/install.sh <version> # paste here the version number obtained above
```

The script will create the executables `~/.bloop/bloop-server`, `~/.bloop/bloop-shell` and 
`~/.bloop/bloop-ng.py`:

 - `bloop-server` is the Bloop server
 - `bloop-ng.py` is the Bloop client
 - `bloop-shell` is a shell that you can use while the Nailgun integration is experimental

We describe how to use Bloop with the experimental Nailgun integration. To see how to use the Bloop
shell, please see the [end of this guide][bloop-shell]

We suggest that you add the following to your shell configuration:

```sh
export PATH="$PATH:~/.bloop"
alias bloop="bloop-ng.py bloop.Cli"
```

The next sections assume that you've added those lines to your profile, and reloaded your shell.

### Installing a released version of Bloop

**Bloop hasn't been released yet, so these instructions won't work!**

Our [installation script][installation-script] lets you install those two components:

```sh
$ curl https://raw.githubusercontent.com/scalacenter/bloop/master/bin/install.sh | sh
```

The script will create the executables `~/.bloop/bloop-server` and `~/.bloop/bloop-ng.py`.

We suggest that you add the following to your shell configuration:

```sh
export PATH="$PATH:~/.bloop"
alias bloop="bloop-ng.py bloop.Cli"
```

The next sections assume that you've added those lines to your profile, and reloaded your shell.

## How to use

### Generate the configuration files

First, we'll need to generate Bloop's configuration files for your project. To
do this, add the following sbt plugin in `project/plugins.sbt` in your project,
where `VERSION_NUMBER` is replaced by the commit your bloop repository is at:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "VERSION_NUMBER")
```

You can then use sbt to generate the configuration:

```sh
$ sbt installBloop
```

### Start the server

Using the server that you previously installed, run:

```sh
$ bloop-server &
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

## Using the Bloop shell

The Bloop shell is an alternative way to use Bloop while the Nailgun integration is still
experimental. It is built along with `bloop-server` and `bloop-ng.py` using out installation script.

To start the Bloop shell, run:

```sh
$ ~/.bloop/bloop-shell <path to your project>
```

[installation-script]: https://raw.githubusercontent.com/scalacenter/bloop/master/bin/install.sh
[bloop-release-post]: http://www.scala-lang.org/blog/2017/11/30/bloop-release.html
[bloop-shell]: #using-the-bloop-shell
