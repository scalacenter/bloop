<p align="center">
  <img src="https://user-images.githubusercontent.com/2462974/32790850-d2eeffc2-c95f-11e7-8a3e-7032ca875ce3.png?style=centerme" alt="g38782" style="max-width:100%;">
</p>

<p align="center">
Bloop gives you fast edit/compile/test workflows for Scala.
</p>

## Goals

Bloop is a tool that focuses on giving you a very fast edit/compile/test loop. It enables you to
perform 99% of the tasks that you usually perform in you build tool, but faster.

It doesn't aim at replacing completely your build tool.

## Installation

The recommended way to install bloop is with Nailgun. To use bloop with Nailgun, you need two
things:

1. The server
1. A nailgun client

### Installing the server

We recommend that you use [Coursier][coursier] to retrieve and package the server as an executable.
Installing Coursier is very simple, and you can find instructions [here][coursier-installation].

Create a standalone executable in `/usr/local/bin/bloop-server` with (add `sudo` if necessary):

```sh
$ coursier bootstrap ch.epfl.scala:bloop_2.12:XXXXX \
    -o /usr/local/bin/bloop-server --standalone --main bloop.Server
```

### Installing the nailgun client

In theory, any nailgun client can work with bloop. However, we suggest that you use our client, as
it it better tested with bloop and uses protocol that is more secure to communicate with nailgun.

Download and install the client in `/usr/local/bin/bloop` with (add `sudo` if necessary):

```sh
$ curl https://raw.githubusercontent.com/scalacenter/nailgun/zinc-nailgun/pynailgun/ng.py \
    -o /usr/local/bin/bloop
```

And that's it! bloop is installed on your machine.

## How to use

### 1. Generate the configuration files

First, we'll need to generate bloop's configuration files for your project. To do this, add the
following sbt plugin in `project/plugins.sbt` in your project:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "XXXXX")
```

You can then use sbt to generate the configuration:

```sh
$ sbt install
```

### 2. Start the server

Using the server that you previously installed, simply run:

```sh
$ /usr/local/bin/bloop-server &
```

Note that you only need to start the server once on your machine, and you can use it with as many
projects as you want, simultaneously.

### 3. Start working!

```
$ bloop compile -p my-project
$ bloop exit
```

## Command reference

#### `about`

The `about` command shows information about bloop.

#### `clean`

The `clean` command resets the state of the incremental compiler for the projects specified with
`-p` or `--projects`. For details, see:

```sh
$ bloop -- clean --help
```

#### `compile`

The `compile` command compiles the project that you specify with `-p` or `--project`, along with
all its dependencies. For details, see:

```sh
$ bloop -- compile --help
```

#### `test`

The `test` command compiles and runs the test in the project specified by `-p`. For details, see:

```sh
$ bloop -- test --help
```

#### `exit`

The `exit` command shuts the bloop server down, and persists all the incremental compiler state on
disk.

[coursier]: http://get-coursier.io
[coursier-installation]: https://github.com/coursier/coursier#command-line
