<p align="center">
  <img src="https://user-images.githubusercontent.com/2462974/32790850-d2eeffc2-c95f-11e7-8a3e-7032ca875ce3.png?style=centerme" alt="g38782" style="max-width:100%;">
</p>

<p align="center">
Bloop gives you fast edit/compile/test workflows for Scala.
</p>

## Goals

Bloop's primary goal is to offer a tight edit/test/debug loop for Scala development.
Bloop accomplishes this goal by executing common tasks like `compile`/`run`/`test`/`console` as
fast as possible.

Bloop aims to complement your build tool, not replace it.

## Installation

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

The next sections assume that you've added those lines to you profile, and reloaded your shell.

## How to use

### 1. Generate the configuration files

First, we'll need to generate Bloop's configuration files for your project. To do this, add the
following sbt plugin in `project/plugins.sbt` in your project:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "XXXXX")
```

You can then use sbt to generate the configuration:

```sh
$ sbt installBloop
```

### 2. Start the server

Using the server that you previously installed, run:

```sh
$ bloop-server &
```

Note that you only need to start the server once on your machine, and you can use it with as many
projects as you want, simultaneously.

### 3. Start working!

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

[installation-script]: https://raw.githubusercontent.com/scalacenter/bloop/master/bin/install.sh
